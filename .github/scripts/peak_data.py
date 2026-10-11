#!/usr/bin/env python3
"""山データの参照先ファイル（site/data/osm-peaks/current.json）を差し替え・確認する。

アプリ（0.6.0 から）は yamamuki の Pages の current.json を読み、そこに書いた manifest のコピー
（site/data/osm-peaks/manifests/<タグ>.json）をたどって、yamamuki-data の Release のデータ本体を取る。

サブコマンド:
  swap         yamamuki-data の Release の manifest をコピーし、current.json の stable か dev を向け直す
               （差し替えの PR を作るワークフローから使う）
  check        PR の内容を確かめる（参照先ファイルの形、コピーが元と同じか、追加だけか、件数の減り方）
  verify-live  公開中の current.json と manifest が git の内容と同じかを確かめる（Pages の公開のあと）
  watch        公開中の current.json からたどれる manifest とデータ本体が取れて中身が合うかを確かめる（毎日の見張り）

標準ライブラリだけで動く。yamamuki-data は公開リポジトリなので、読むのに特別な権限は要らない
（GH_TOKEN があれば API の回数制限を避けるために使う）。
"""

import argparse
import gzip
import hashlib
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SITE_DIR = Path("site/data/osm-peaks")
POINTER_NAME = "current.json"
MANIFESTS_NAME = "manifests"

DATA_REPO = "shohei0205/yamamuki-data"
PAGES_BASE = "https://shohei0205.github.io/yamamuki/data/osm-peaks/"
MANIFEST_URL_PREFIX = PAGES_BASE + MANIFESTS_NAME + "/"
DOWNLOAD_PREFIX = f"https://github.com/{DATA_REPO}/releases/download/"

POINTER_SCHEMA_VERSION = 1
CHANNELS = ("stable", "dev")

# 配布用（stable）に書いてよい manifest の形式。配布中のアプリが読める版だけにする。
# 新しい形式は dev で試し、読めるアプリが行き渡ってから、ここに足して stable に使う。
STABLE_SCHEMA_VERSIONS = {5}
STABLE_DATA_SCHEMA_VERSIONS = {5}

STABLE_TAG = re.compile(r"osm-peaks-\d{8}T\d{6}Z-\d+-\d+")
DEV_TAG = re.compile(r"osm-peaks-dev-\d{8}T\d{6}Z-\d+-\d+")

# 前の版から件数がこれより多く減っていたら、PR の確認を失敗にする（ラベルで通せる）。
MAX_COUNT_DROP = 0.05
COUNT_DROP_LABEL = "山データの件数減を確認済み"
MAX_DATA_BYTES = 20_000_000


class PeakDataError(Exception):
    """確認で見つかった問題。メッセージはそのまま実行結果に出す。"""


# ---- 通信 ----------------------------------------------------------------

def http_get(url, *, accept=None, token=None, attempts=3):
    headers = {"User-Agent": "yamamuki-peak-data", "Cache-Control": "no-cache"}
    if accept:
        headers["Accept"] = accept
    if token:
        headers["Authorization"] = f"Bearer {token}"
    last = None
    for attempt in range(attempts):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=60) as response:
                return response.read()
        except urllib.error.HTTPError as e:
            if e.code == 404:
                raise PeakDataError(f"見つかりません（404）: {url}") from e
            last = e
        except urllib.error.URLError as e:
            last = e
        time.sleep(5 * (attempt + 1))
    raise PeakDataError(f"取得できません: {url}（{last}）")


def api(path):
    token = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
    body = http_get(f"https://api.github.com/repos/{DATA_REPO}/{path}",
                    accept="application/vnd.github+json", token=token)
    return json.loads(body)


def release(tag):
    return api(f"releases/tags/{tag}")


def releases():
    return api("releases?per_page=100")


def release_manifest(tag):
    """Release の manifest.json のバイト列と、Release の情報を返す。"""
    info = release(tag)
    if info.get("draft"):
        raise PeakDataError(f"{tag} は下書きの Release です")
    assets = [a for a in info.get("assets", []) if a.get("name") == "manifest.json"]
    if len(assets) != 1:
        raise PeakDataError(f"{tag} の Release に manifest.json がありません")
    return http_get(assets[0]["browser_download_url"]), info


# ---- 形の確認 ------------------------------------------------------------

def tag_channel(tag):
    """タグが正式版なら stable、開発版なら dev を返す。どちらでもなければ None。"""
    if STABLE_TAG.fullmatch(tag):
        return "stable"
    if DEV_TAG.fullmatch(tag):
        return "dev"
    return None


def tag_from_manifest_url(url):
    if not url.startswith(MANIFEST_URL_PREFIX) or not url.endswith(".json"):
        raise PeakDataError(f"manifestUrl が {MANIFEST_URL_PREFIX} の中のファイルを指していません: {url}")
    tag = url[len(MANIFEST_URL_PREFIX):-len(".json")]
    if tag_channel(tag) is None:
        raise PeakDataError(f"manifestUrl のファイル名が Release のタグの形ではありません: {url}")
    return tag


def validate_pointer(pointer):
    """参照先ファイルの形を確かめ、{チャンネル: タグ} を返す。"""
    if not isinstance(pointer, dict):
        raise PeakDataError("current.json がオブジェクトではありません")
    if pointer.get("schemaVersion") != POINTER_SCHEMA_VERSION:
        raise PeakDataError(f"current.json の schemaVersion は {POINTER_SCHEMA_VERSION} にしてください")
    unknown = set(pointer) - {"schemaVersion", *CHANNELS}
    if unknown:
        raise PeakDataError(f"current.json に知らない項目があります: {', '.join(sorted(unknown))}")
    if "stable" not in pointer:
        raise PeakDataError("current.json に stable がありません")
    tags = {}
    for channel in CHANNELS:
        if channel not in pointer:
            continue
        entry = pointer[channel]
        if not isinstance(entry, dict) or set(entry) != {"manifestUrl", "manifestSha256"}:
            raise PeakDataError(f"current.json の {channel} は manifestUrl と manifestSha256 だけにしてください")
        if not re.fullmatch(r"[0-9a-f]{64}", str(entry["manifestSha256"])):
            raise PeakDataError(f"current.json の {channel} の manifestSha256 が SHA-256 の形ではありません")
        tag = tag_from_manifest_url(entry["manifestUrl"])
        if channel == "stable" and tag_channel(tag) != "stable":
            raise PeakDataError(f"stable には正式版の manifest だけを書けます: {tag}")
        tags[channel] = tag
    return tags


def validate_manifest(data, tag, channel):
    """コピーする manifest を確かめ、読んだ内容を返す。stable では配布中のアプリが読める形式かも見る。"""
    try:
        manifest = json.loads(data)
    except ValueError as e:
        raise PeakDataError(f"{tag} の manifest を JSON として読めません") from e
    if not isinstance(manifest, dict):
        raise PeakDataError(f"{tag} の manifest がオブジェクトではありません")
    schema = manifest.get("schemaVersion")
    data_schema = manifest.get("dataSchemaVersion")
    if channel == "stable" and (schema not in STABLE_SCHEMA_VERSIONS or data_schema not in STABLE_DATA_SCHEMA_VERSIONS):
        raise PeakDataError(
            f"{tag} の形式（schemaVersion {schema}、dataSchemaVersion {data_schema}）は、配布中のアプリが読める形式"
            f"（schemaVersion {sorted(STABLE_SCHEMA_VERSIONS)}、dataSchemaVersion {sorted(STABLE_DATA_SCHEMA_VERSIONS)}）"
            "ではないので、stable には使えません。新しい形式は dev で試してください。")
    for key, kind in (("version", str), ("downloadUrl", str), ("sha256", str), ("sizeBytes", int), ("pointCount", int)):
        if not isinstance(manifest.get(key), kind) or isinstance(manifest.get(key), bool):
            raise PeakDataError(f"{tag} の manifest の {key} がないか、形が違います")
    prefix = "osm-peaks-dev-" if tag_channel(tag) == "dev" else "osm-peaks-"
    if prefix + manifest["version"] != tag:
        raise PeakDataError(f"{tag} の manifest の version（{manifest['version']}）がタグと合いません")
    if not manifest["downloadUrl"].startswith(f"{DOWNLOAD_PREFIX}{tag}/"):
        raise PeakDataError(f"{tag} の manifest の downloadUrl が同じ Release を指していません")
    if not re.fullmatch(r"[0-9a-f]{64}", manifest["sha256"]):
        raise PeakDataError(f"{tag} の manifest の sha256 が SHA-256 の形ではありません")
    if not 0 < manifest["sizeBytes"] <= MAX_DATA_BYTES:
        raise PeakDataError(f"{tag} の manifest の sizeBytes（{manifest['sizeBytes']}）は受け取れません")
    return manifest


def check_data(manifest, tag):
    """データ本体を取り、大きさと SHA-256 を確かめ、展開して件数を返す。"""
    body = http_get(manifest["downloadUrl"])
    if len(body) != manifest["sizeBytes"]:
        raise PeakDataError(f"{tag} のデータ本体の大きさが合いません（{len(body)} バイト、manifest では {manifest['sizeBytes']} バイト）")
    if hashlib.sha256(body).hexdigest() != manifest["sha256"]:
        raise PeakDataError(f"{tag} のデータ本体の SHA-256 が合いません")
    try:
        items = json.loads(gzip.decompress(body))
    except (OSError, ValueError) as e:
        raise PeakDataError(f"{tag} のデータ本体を展開して読めません") from e
    if not isinstance(items, list):
        raise PeakDataError(f"{tag} のデータ本体が配列ではありません")
    return len(items)


def count_drop(old, new):
    """件数の減った割合（増えたときは 0）。"""
    if not old or new >= old:
        return 0.0
    return (old - new) / old


def sha256(data):
    return hashlib.sha256(data).hexdigest()


# ---- ファイル ------------------------------------------------------------

def pointer_path(root):
    return root / SITE_DIR / POINTER_NAME


def manifest_path(root, tag):
    return root / SITE_DIR / MANIFESTS_NAME / f"{tag}.json"


def read_pointer(root):
    return json.loads(pointer_path(root).read_text(encoding="utf-8"))


def write_pointer(root, pointer):
    ordered = {"schemaVersion": pointer["schemaVersion"]}
    for channel in CHANNELS:
        if channel in pointer:
            ordered[channel] = pointer[channel]
    pointer_path(root).write_text(json.dumps(ordered, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")


def entry_for(tag, data):
    return {"manifestUrl": f"{MANIFEST_URL_PREFIX}{tag}.json", "manifestSha256": sha256(data)}


def local_manifest(root, tag):
    path = manifest_path(root, tag)
    if not path.exists():
        raise PeakDataError(f"{path.relative_to(root)} がありません")
    return path.read_bytes()


# ---- swap ----------------------------------------------------------------

def latest_tag(target):
    """target が stable ならいちばん新しい正式版、dev ならいちばん新しい開発版のタグ。"""
    candidates = [r for r in releases() if not r.get("draft") and tag_channel(r.get("tag_name", "")) == target
                  and bool(r.get("prerelease")) == (target == "dev")]
    if not candidates:
        raise PeakDataError(f"yamamuki-data に {'正式版' if target == 'stable' else '開発版'} の Release がありません")
    return max(candidates, key=lambda r: r.get("published_at") or r.get("created_at") or "")["tag_name"]


def describe(root, tag):
    """PR の本文に書く、版と件数の説明。"""
    if tag is None:
        return "なし"
    manifest = json.loads(local_manifest(root, tag))
    return f"`{tag}`（{manifest['pointCount']:,} 件）"


def swap(root, target, tag, remove_dev):
    """current.json を書き換える。変わったかどうかと、PR に使う件名・本文・ブランチ名を返す。"""
    pointer = read_pointer(root)
    before = validate_pointer(pointer)
    lines = []

    if target == "remove-dev":
        if "dev" not in pointer:
            return {"changed": False, "message": "current.json に dev はありません。何もしません。"}
        del pointer["dev"]
        write_pointer(root, pointer)
        return {
            "changed": True,
            "branch": "data/remove-dev",
            "title": "feat: 開発用の山データの指定（dev）を外す",
            "body": [f"- 開発用のビルドの読み先（`dev`）を外す。開発用のビルドも `stable` の {describe(root, before['stable'])} を読む。",
                     f"- 外す前の `dev`: {describe(root, before['dev'])}"],
        }

    tag = tag or latest_tag(target)
    channel = tag_channel(tag)
    if channel is None:
        raise PeakDataError(f"Release のタグの形ではありません: {tag}")
    if target == "stable" and channel != "stable":
        raise PeakDataError(f"stable には正式版（osm-peaks-…）だけを使えます: {tag}")

    data, info = release_manifest(tag)
    if target == "stable" and info.get("prerelease"):
        raise PeakDataError(f"{tag} はプレリリースなので stable には使えません")
    manifest = validate_manifest(data, tag, target)

    path = manifest_path(root, tag)
    if path.exists() and path.read_bytes() != data:
        raise PeakDataError(f"{path.relative_to(root)} はもうあり、Release の manifest.json と中身が違います。"
                            "一度置いたファイルは書き換えません。")
    entry = entry_for(tag, data)
    changed = pointer.get(target) != entry
    if target == "stable" and remove_dev and "dev" in pointer:
        del pointer["dev"]
        changed = True
    if not changed:
        return {"changed": False, "message": f"current.json の {target} はもう {tag} を指しています。何もしません。"}

    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)
    pointer[target] = entry
    write_pointer(root, pointer)

    old_tag = before.get(target)
    old_count = json.loads(local_manifest(root, old_tag))["pointCount"] if old_tag else None
    new_count = manifest["pointCount"]
    since = f"{describe(root, old_tag)} から " if old_tag else ""
    if target == "stable":
        title = f"feat: 配布用の山データを {tag} に差し替える"
        lines.append(f"- 配布用のビルドが読む山データ（`stable`）を {since}`{tag}`（{new_count:,} 件）に差し替える。")
    else:
        title = f"feat: 開発用の山データ（dev）を {tag} に向ける"
        lines.append(f"- 開発用のビルドが読む山データ（`dev`）を {since}`{tag}`（{new_count:,} 件）に向ける。"
                     "配布用（`stable`）は変えない。")
    if old_count is not None:
        diff = new_count - old_count
        lines.append(f"- 件数の差: {diff:+,} 件（{old_count:,} → {new_count:,}）。")
    if target == "stable" and remove_dev and "dev" in before:
        lines.append(f"- 開発用の指定（`dev`、{describe(root, before['dev'])}）も外す。")
    lines.append(f"- 形式: schemaVersion {manifest.get('schemaVersion')}、dataSchemaVersion {manifest.get('dataSchemaVersion')}。")
    lines.append(f"- Release: {info.get('html_url', '')}")
    if "要確認" in (info.get("body") or ""):
        lines.append("- **Release の本文に「要確認」があります。** 理由を読んでから決めてください。")
    lines.append(f"- `site/data/osm-peaks/manifests/{tag}.json` に Release の manifest.json を書き換えずにコピーした。")
    return {"changed": True, "branch": f"data/{target}/{tag}", "title": title, "body": lines}


# ---- check ---------------------------------------------------------------

def git(*args):
    return subprocess.run(["git", *args], cwd=ROOT, check=True, capture_output=True, text=True).stdout


def manifest_changes(name_status):
    """git diff --name-status の出力から、manifests/ の追加されたファイルと、それ以外の変更を分ける。"""
    added, others = [], []
    prefix = f"{SITE_DIR.as_posix()}/{MANIFESTS_NAME}/"
    for line in name_status.splitlines():
        if not line.strip():
            continue
        status, *paths = line.split("\t")
        if not any(p.startswith(prefix) for p in paths):
            continue
        if status == "A":
            added.append(paths[0])
        else:
            others.append(f"{status} {' '.join(paths)}")
    return added, others


def check(root, base, allow_count_drop):
    problems = []
    notes = []

    def attempt(func, *args):
        try:
            return func(*args)
        except PeakDataError as e:
            problems.append(str(e))
            return None

    pointer = read_pointer(root)
    tags = validate_pointer(pointer)

    for channel, tag in tags.items():
        data = attempt(local_manifest, root, tag)
        if data is None:
            continue
        if sha256(data) != pointer[channel]["manifestSha256"]:
            problems.append(f"current.json の {channel} の manifestSha256 が {tag}.json の SHA-256 と合いません")
        manifest = attempt(validate_manifest, data, tag, channel)
        if manifest is None:
            continue
        count = attempt(check_data, manifest, tag)
        if count is not None:
            notes.append(f"{channel}: {tag}（manifest では {manifest['pointCount']:,} 件、データ本体の配列は {count:,} 件）")

    added, others = manifest_changes(git("diff", "--name-status", f"{base}...HEAD", "--", (SITE_DIR / MANIFESTS_NAME).as_posix()))
    for change in others:
        problems.append(f"manifests/ の既存のファイルは書き換え・削除できません（追加だけ）: {change}")
    for path in added:
        tag = Path(path).stem
        if tag_channel(tag) is None:
            problems.append(f"manifests/ に Release のタグの形ではない名前のファイルがあります: {path}")
            continue
        copy = (root / path).read_bytes()
        original = attempt(release_manifest, tag)
        if original is not None and original[0] != copy:
            problems.append(f"{path} が Release {tag} の manifest.json と 1 バイト以上違います")

    try:
        base_pointer = json.loads(git("show", f"{base}:{(SITE_DIR / POINTER_NAME).as_posix()}"))
        base_tag = validate_pointer(base_pointer)["stable"]
        old = json.loads(git("show", f"{base}:{(SITE_DIR / MANIFESTS_NAME).as_posix()}/{base_tag}.json"))["pointCount"]
        new = json.loads(local_manifest(root, tags["stable"]))["pointCount"]
        drop = count_drop(old, new)
        if drop > MAX_COUNT_DROP:
            message = (f"stable の件数が {drop:.1%} 減っています（{old:,} → {new:,}）。"
                       f"意図した減少なら、PR にラベル「{COUNT_DROP_LABEL}」を付けてください。")
            (notes if allow_count_drop else problems).append(message)
    except subprocess.CalledProcessError:
        notes.append("main に current.json がまだ無いので、件数の比較はしません。")

    for note in notes:
        print(f"確認: {note}")
    if problems:
        for problem in problems:
            print(f"::error::{problem}")
        raise PeakDataError(f"{len(problems)} 件の問題があります")
    print("山データの参照先ファイルはルールどおりです。")


# ---- verify-live / watch -------------------------------------------------

def verify_live(root, attempts, interval):
    """公開中の current.json と、それが指す manifest が git の内容と同じになるまで、何度か取り直す。"""
    pointer_bytes = pointer_path(root).read_bytes()
    tags = validate_pointer(json.loads(pointer_bytes))
    expected = {PAGES_BASE + POINTER_NAME: pointer_bytes}
    for tag in tags.values():
        expected[f"{MANIFEST_URL_PREFIX}{tag}.json"] = local_manifest(root, tag)
    mismatched = []
    for attempt in range(attempts):
        mismatched = []
        for url, data in expected.items():
            try:
                live = http_get(f"{url}?t={int(time.time())}")
            except PeakDataError as e:
                mismatched.append(str(e))
                continue
            if live != data:
                mismatched.append(f"公開中の内容が git と違います: {url}")
        if not mismatched:
            print("公開中の参照先ファイルと manifest は git と同じです。")
            return
        if attempt + 1 < attempts:
            time.sleep(interval)
    for message in mismatched:
        print(f"::error::{message}")
    raise PeakDataError("公開中の内容が git と合いません")


def watch():
    """公開中の current.json から、manifest とデータ本体までたどれるかを確かめる。"""
    problems = []
    pointer = json.loads(http_get(f"{PAGES_BASE}{POINTER_NAME}?t={int(time.time())}"))
    tags = validate_pointer(pointer)
    for channel, tag in tags.items():
        try:
            data = http_get(pointer[channel]["manifestUrl"])
            if sha256(data) != pointer[channel]["manifestSha256"]:
                raise PeakDataError(f"{channel}: 公開中の {tag}.json の SHA-256 が current.json と合いません")
            manifest = validate_manifest(data, tag, channel)
            count = check_data(manifest, tag)
            print(f"確認: {channel}: {tag}（{count:,} 件）を取得できました。")
        except PeakDataError as e:
            problems.append(f"{channel}: {e}")
    if problems:
        for problem in problems:
            print(f"::error::{problem}")
        raise PeakDataError("\n".join(problems))


# ---- 入口 ----------------------------------------------------------------

def write_outputs(result):
    """GitHub Actions のステップの出力と、PR の本文のファイルを書く。"""
    output = os.environ.get("GITHUB_OUTPUT")
    lines = [f"changed={'true' if result['changed'] else 'false'}"]
    if result["changed"]:
        body_file = Path(os.environ.get("RUNNER_TEMP", "/tmp")) / "peak-data-pr-body.md"
        body_file.write_text("\n".join([
            "## 何を変えたか", "", *result["body"], "",
            "## 対象", "", "- [ ] Android (android/)", "- [ ] iOS (ios/)", "- [x] その他 (CI, ドキュメントなど)", "",
            "## 確認方法", "",
            "- PR の CI「山データの確認」が、コピーが Release と同じこと・追加だけであること・データ本体の大きさと SHA-256・件数の減り方を確かめる。",
            "- 「Build」の core のテストが、置いた manifest をアプリの読み込み処理で読めることを確かめる。",
            "- マージ後、Pages の公開のあとに、公開中の内容が git と同じかを確かめる。", "",
            "この PR は「山データの差し替え PR を作る」ワークフローが作った。",
        ]) + "\n", encoding="utf-8")
        lines += [f"branch={result['branch']}", f"title={result['title']}", f"body_file={body_file}"]
    else:
        print(result["message"])
    if output:
        with open(output, "a", encoding="utf-8") as f:
            f.write("\n".join(lines) + "\n")
    else:
        print("\n".join(lines))


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="command", required=True)
    p = sub.add_parser("swap")
    p.add_argument("--target", choices=["stable", "dev", "remove-dev"], required=True)
    p.add_argument("--tag", default="")
    p.add_argument("--remove-dev", action="store_true")
    p = sub.add_parser("check")
    p.add_argument("--base", required=True)
    p = sub.add_parser("verify-live")
    p.add_argument("--attempts", type=int, default=10)
    p.add_argument("--interval", type=int, default=30)
    sub.add_parser("watch")
    args = parser.parse_args(argv)
    try:
        if args.command == "swap":
            write_outputs(swap(ROOT, args.target, args.tag.strip(), args.remove_dev))
        elif args.command == "check":
            check(ROOT, args.base, os.environ.get("ALLOW_COUNT_DROP") == "true")
        elif args.command == "verify-live":
            verify_live(ROOT, args.attempts, args.interval)
        else:
            watch()
    except PeakDataError as e:
        print(f"::error::{e}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
