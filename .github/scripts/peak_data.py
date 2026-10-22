#!/usr/bin/env python3
"""山データのカタログ（site/data/catalog.json）と参照先ファイル（site/data/osm-peaks/current.json）を差し替え・確認する。

アプリ（0.8.0 から）は yamamuki の Pages の catalog.json を読み、そこに書いた osm-peaks の manifest のコピー
（site/data/osm-peaks/manifests/<タグ>.json）をたどって、yamamuki-data の Release のデータ本体を取る。
カタログには、アプリがまだ読まないデータ（gsi-peaks）の manifest も、あとで読めるように入れておく。
current.json は 0.6.0・0.7.0 が読む参照先ファイルで、0.8.0 の間は osm-peaks の stable と同じ manifest を指す。

サブコマンド:
  swap         yamamuki-data の Release の manifest をコピーし、カタログの stable か dev を向け直す
               （差し替えの PR を作るワークフローから使う）
  check        PR の内容を確かめる（カタログと参照先ファイルの形、コピーが元と同じか、追加だけか、件数の減り方）
  verify-live  公開中のカタログ・参照先ファイル・manifest が git の内容と同じかを確かめる（Pages の公開のあと）
  watch        公開中のカタログからたどれる manifest とデータ本体が取れて中身が合うかを確かめる（毎日の見張り）

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
SITE_DATA_DIR = Path("site/data")
CATALOG_NAME = "catalog.json"
POINTER_DATASET = "osm-peaks"
POINTER_NAME = "current.json"
MANIFESTS_NAME = "manifests"

DATA_REPO = "shohei0205/yamamuki-data"
PAGES_DATA_BASE = "https://shohei0205.github.io/yamamuki/data/"
DOWNLOAD_PREFIX = f"https://github.com/{DATA_REPO}/releases/download/"

CATALOG_SCHEMA_VERSION = 1
POINTER_SCHEMA_VERSION = 1
CHANNELS = ("stable", "dev")

# カタログに入れるデータの種類。アプリの設定「山データ」で選ぶ(0.8.0 から)。osm-peaks は既定の種類で、stable は必ず置く。
# gsi-peaks は stable も dev も省略できる(stable が無ければ、配布用のアプリの選択画面に出ない)。
DATASETS = ("osm-peaks", "gsi-peaks")
APP_DATASET = "osm-peaks"
DATASET_LABELS = {"osm-peaks": "山データ", "gsi-peaks": "国土地理院の山データ（gsi-peaks）"}

# 配布用（stable）の osm-peaks に書いてよい manifest の形式。配布中のアプリが読める版だけにする。
# 新しい形式は dev で試し、読めるアプリが行き渡ってから、ここに足して stable に使う。
STABLE_SCHEMA_VERSIONS = {5}
STABLE_DATA_SCHEMA_VERSIONS = {5}

# manifest 版 6 から入った、形式の名前（schemaFormat・dataSchemaFormat）。
MANIFEST_SCHEMA_FORMAT = "yamamuki-manifest"
DATA_SCHEMA_FORMAT = "yamamuki-points"

TAG = re.compile(r"(?P<dataset>[a-z]+(?:-[a-z]+)*?)-(?P<dev>dev-)?\d{8}T\d{6}Z-\d+-\d+")

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


def releases(max_pages=50):
    """yamamuki-data の Release を、ページをめくってすべて返す（新しい順）。"""
    items = []
    for page in range(1, max_pages + 1):
        listing = api(f"releases?per_page=100&page={page}")
        items.extend(listing)
        if len(listing) < 100:
            return items
    raise PeakDataError(f"yamamuki-data の Release が {max_pages * 100} 件を超えていて、すべてを読めません")


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

def parse_tag(tag):
    """カタログに入れるデータの Release のタグなら（データの種類, stable か dev）を返す。違えば None。"""
    match = TAG.fullmatch(tag)
    if not match or match["dataset"] not in DATASETS:
        return None
    return match["dataset"], "dev" if match["dev"] else "stable"


def manifest_url_prefix(dataset):
    return f"{PAGES_DATA_BASE}{dataset}/{MANIFESTS_NAME}/"


def tag_from_manifest_url(url, dataset):
    prefix = manifest_url_prefix(dataset)
    if not isinstance(url, str) or not url.startswith(prefix) or not url.endswith(".json"):
        raise PeakDataError(f"manifestUrl が {prefix} の中のファイルを指していません: {url}")
    tag = url[len(prefix):-len(".json")]
    if parse_tag(tag) is None or parse_tag(tag)[0] != dataset:
        raise PeakDataError(f"manifestUrl のファイル名が {dataset} の Release のタグの形ではありません: {url}")
    return tag


def validate_entry(entry, dataset, channel, where):
    """カタログと参照先ファイルの、manifest の URL と SHA-256 の組を確かめ、タグを返す。"""
    if not isinstance(entry, dict) or set(entry) != {"manifestUrl", "manifestSha256"}:
        raise PeakDataError(f"{where} の {channel} は manifestUrl と manifestSha256 だけにしてください")
    if not re.fullmatch(r"[0-9a-f]{64}", str(entry["manifestSha256"])):
        raise PeakDataError(f"{where} の {channel} の manifestSha256 が SHA-256 の形ではありません")
    tag = tag_from_manifest_url(entry["manifestUrl"], dataset)
    if channel == "stable" and parse_tag(tag)[1] != "stable":
        raise PeakDataError(f"{where} の stable には正式版の manifest だけを書けます: {tag}")
    return tag


def validate_catalog(catalog):
    """カタログの形を確かめ、{データの種類: {チャンネル: タグ}} を返す。"""
    if not isinstance(catalog, dict):
        raise PeakDataError("catalog.json がオブジェクトではありません")
    if catalog.get("schemaVersion") != CATALOG_SCHEMA_VERSION:
        raise PeakDataError(f"catalog.json の schemaVersion は {CATALOG_SCHEMA_VERSION} にしてください")
    unknown = set(catalog) - {"schemaVersion", "datasets"}
    if unknown:
        raise PeakDataError(f"catalog.json に知らない項目があります: {', '.join(sorted(unknown))}")
    datasets = catalog.get("datasets")
    if not isinstance(datasets, dict):
        raise PeakDataError("catalog.json に datasets がありません")
    unknown = set(datasets) - set(DATASETS)
    if unknown:
        raise PeakDataError(f"catalog.json に知らないデータの種類があります: {', '.join(sorted(unknown))}")
    if APP_DATASET not in datasets:
        raise PeakDataError(f"catalog.json に {APP_DATASET} がありません")
    tags = {}
    for dataset in DATASETS:
        if dataset not in datasets:
            continue
        channels = datasets[dataset]
        where = f"catalog.json の {dataset}"
        if not isinstance(channels, dict) or not channels:
            raise PeakDataError(f"{where} が空か、オブジェクトではありません")
        unknown = set(channels) - set(CHANNELS)
        if unknown:
            raise PeakDataError(f"{where} に知らない項目があります: {', '.join(sorted(unknown))}")
        if dataset == APP_DATASET and "stable" not in channels:
            raise PeakDataError(f"{where} に stable がありません")
        tags[dataset] = {channel: validate_entry(channels[channel], dataset, channel, where)
                         for channel in CHANNELS if channel in channels}
    return tags


def validate_pointer(pointer):
    """参照先ファイル（0.6.0・0.7.0 が読む）の形を確かめ、stable のタグを返す。dev はもう書かない。"""
    if not isinstance(pointer, dict):
        raise PeakDataError("current.json がオブジェクトではありません")
    if pointer.get("schemaVersion") != POINTER_SCHEMA_VERSION:
        raise PeakDataError(f"current.json の schemaVersion は {POINTER_SCHEMA_VERSION} にしてください")
    unknown = set(pointer) - {"schemaVersion", "stable"}
    if unknown:
        raise PeakDataError(f"current.json に知らない項目があります（dev はカタログに書きます）: {', '.join(sorted(unknown))}")
    if "stable" not in pointer:
        raise PeakDataError("current.json に stable がありません")
    return validate_entry(pointer["stable"], POINTER_DATASET, "stable", "current.json")


def validate_manifest(data, tag, channel):
    """コピーする manifest を確かめ、読んだ内容を返す。osm-peaks の stable では配布中のアプリが読める形式かも見る。"""
    dataset = parse_tag(tag)[0]
    try:
        manifest = json.loads(data)
    except ValueError as e:
        raise PeakDataError(f"{tag} の manifest を JSON として読めません") from e
    if not isinstance(manifest, dict):
        raise PeakDataError(f"{tag} の manifest がオブジェクトではありません")
    schema = manifest.get("schemaVersion")
    data_schema = manifest.get("dataSchemaVersion")
    if dataset == APP_DATASET and channel == "stable" and (
            schema not in STABLE_SCHEMA_VERSIONS or data_schema not in STABLE_DATA_SCHEMA_VERSIONS):
        raise PeakDataError(
            f"{tag} の形式（schemaVersion {schema}、dataSchemaVersion {data_schema}）は、配布中のアプリが読める形式"
            f"（schemaVersion {sorted(STABLE_SCHEMA_VERSIONS)}、dataSchemaVersion {sorted(STABLE_DATA_SCHEMA_VERSIONS)}）"
            "ではないので、stable には使えません。新しい形式は dev で試してください。")
    for key, kind in (("version", str), ("fileName", str), ("downloadUrl", str), ("sha256", str), ("sizeBytes", int)):
        if not isinstance(manifest.get(key), kind) or isinstance(manifest.get(key), bool):
            raise PeakDataError(f"{tag} の manifest の {key} がないか、形が違います")
    if dataset != APP_DATASET and not is_v6(manifest):
        raise PeakDataError(f"{tag} の manifest は版 6 以降にしてください（schemaVersion {schema}）")
    if is_v6(manifest):
        if manifest.get("schemaFormat") != MANIFEST_SCHEMA_FORMAT:
            raise PeakDataError(f"{tag} の manifest の schemaFormat が {MANIFEST_SCHEMA_FORMAT} ではありません")
        if manifest.get("dataSchemaFormat") != DATA_SCHEMA_FORMAT:
            raise PeakDataError(f"{tag} の manifest の dataSchemaFormat が {DATA_SCHEMA_FORMAT} ではありません")
    count = point_count(manifest)
    if not isinstance(count, int) or isinstance(count, bool):
        key = "metadata.pointCount" if is_v6(manifest) else "pointCount"
        raise PeakDataError(f"{tag} の manifest の {key} がないか、形が違います")
    prefix = f"{dataset}-dev-" if parse_tag(tag)[1] == "dev" else f"{dataset}-"
    if prefix + manifest["version"] != tag:
        raise PeakDataError(f"{tag} の manifest の version（{manifest['version']}）がタグと合いません")
    # アプリはデータの種類を manifest の fileName でも確かめる（取り違えると保存済みの山が消えるため）。
    if manifest["fileName"] != f"{dataset}.json.gz":
        raise PeakDataError(f"{tag} の manifest の fileName（{manifest['fileName']}）が {dataset}.json.gz ではありません")
    if manifest["downloadUrl"] != f"{DOWNLOAD_PREFIX}{tag}/{manifest['fileName']}":
        raise PeakDataError(f"{tag} の manifest の downloadUrl が同じ Release の {manifest['fileName']} を指していません")
    if not re.fullmatch(r"[0-9a-f]{64}", manifest["sha256"]):
        raise PeakDataError(f"{tag} の manifest の sha256 が SHA-256 の形ではありません")
    if not 0 < manifest["sizeBytes"] <= MAX_DATA_BYTES:
        raise PeakDataError(f"{tag} の manifest の sizeBytes（{manifest['sizeBytes']}）は受け取れません")
    return manifest


def is_v6(manifest):
    """版 6 以降の manifest か。版 6 で件数と出典が metadata にまとまった。"""
    schema = manifest.get("schemaVersion")
    return isinstance(schema, int) and not isinstance(schema, bool) and schema >= 6


def point_count(manifest):
    """manifest の件数。版 5 は pointCount、版 6 以降は metadata.pointCount。無ければ None。"""
    if is_v6(manifest):
        metadata = manifest.get("metadata")
        return metadata.get("pointCount") if isinstance(metadata, dict) else None
    return manifest.get("pointCount")


def check_data(manifest, tag):
    """データ本体を取り、大きさと SHA-256 を確かめ、展開して件数を返す。
    版 5 までは地点の配列、版 6 は points に地点の配列を持つ文書。"""
    body = http_get(manifest["downloadUrl"])
    if len(body) != manifest["sizeBytes"]:
        raise PeakDataError(f"{tag} のデータ本体の大きさが合いません（{len(body)} バイト、manifest では {manifest['sizeBytes']} バイト）")
    if hashlib.sha256(body).hexdigest() != manifest["sha256"]:
        raise PeakDataError(f"{tag} のデータ本体の SHA-256 が合いません")
    try:
        items = json.loads(gzip.decompress(body))
    except (OSError, ValueError) as e:
        raise PeakDataError(f"{tag} のデータ本体を展開して読めません") from e
    if isinstance(items, dict):
        items = items.get("points")
    if not isinstance(items, list):
        raise PeakDataError(f"{tag} のデータ本体が、地点の配列でも points の配列を持つ文書でもありません")
    return len(items)


def count_drop(old, new):
    """件数の減った割合（増えたときは 0）。"""
    if not old or new >= old:
        return 0.0
    return (old - new) / old


def sha256(data):
    return hashlib.sha256(data).hexdigest()


# ---- ファイル ------------------------------------------------------------

def catalog_path(root):
    return root / SITE_DATA_DIR / CATALOG_NAME


def pointer_path(root):
    return root / SITE_DATA_DIR / POINTER_DATASET / POINTER_NAME


def manifests_dir(dataset):
    return SITE_DATA_DIR / dataset / MANIFESTS_NAME


def manifest_path(root, tag):
    return root / manifests_dir(parse_tag(tag)[0]) / f"{tag}.json"


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")


def read_catalog(root):
    return json.loads(catalog_path(root).read_text(encoding="utf-8"))


def write_catalog(root, catalog):
    datasets = {}
    for dataset in DATASETS:
        channels = catalog["datasets"].get(dataset)
        if channels:
            datasets[dataset] = {channel: channels[channel] for channel in CHANNELS if channel in channels}
    write_json(catalog_path(root), {"schemaVersion": catalog["schemaVersion"], "datasets": datasets})


def read_pointer(root):
    """参照先ファイルを読む。0.9.0 で無くす予定なので、無ければ None。"""
    path = pointer_path(root)
    return json.loads(path.read_text(encoding="utf-8")) if path.exists() else None


def write_pointer(root, pointer):
    write_json(pointer_path(root), {"schemaVersion": pointer["schemaVersion"], "stable": pointer["stable"]})


def entry_for(tag, data):
    return {"manifestUrl": f"{manifest_url_prefix(parse_tag(tag)[0])}{tag}.json", "manifestSha256": sha256(data)}


def local_manifest(root, tag):
    path = manifest_path(root, tag)
    if not path.exists():
        raise PeakDataError(f"{path.relative_to(root)} がありません")
    return path.read_bytes()


# ---- swap ----------------------------------------------------------------

def latest_tag(dataset, target):
    """dataset の、target が stable ならいちばん新しい正式版、dev ならいちばん新しい開発版のタグ。"""
    candidates = [r for r in releases() if not r.get("draft") and parse_tag(r.get("tag_name", "")) == (dataset, target)
                  and bool(r.get("prerelease")) == (target == "dev")]
    if not candidates:
        raise PeakDataError(f"yamamuki-data に {dataset} の{'正式版' if target == 'stable' else '開発版'}の Release がありません")
    return max(candidates, key=lambda r: r.get("published_at") or r.get("created_at") or "")["tag_name"]


def describe(root, tag):
    """PR の本文に書く、版と件数の説明。"""
    if tag is None:
        return "なし"
    manifest = json.loads(local_manifest(root, tag))
    return f"`{tag}`（{point_count(manifest):,} 件）"


def swap(root, dataset, target, tag, remove_dev):
    """カタログを書き換える。変わったかどうかと、PR に使う件名・本文・ブランチ名を返す。
    osm-peaks の stable を変えたときは、参照先ファイル（current.json）の stable も同じ manifest にする。"""
    if dataset not in DATASETS:
        raise PeakDataError(f"知らないデータの種類です: {dataset}")
    catalog = read_catalog(root)
    before = validate_catalog(catalog).get(dataset, {})
    channels = catalog["datasets"].setdefault(dataset, {})
    label = DATASET_LABELS[dataset]
    lines = []

    if target == "remove-dev":
        if "dev" not in channels:
            return {"changed": False, "message": f"catalog.json の {dataset} に dev はありません。何もしません。"}
        del channels["dev"]
        write_catalog(root, catalog)
        if dataset == APP_DATASET:
            title = "feat: 開発用の山データの指定（dev）を外す"
            first = f"- 開発用のビルドの読み先（`dev`）を外す。開発用のビルドも `stable` の {describe(root, before['stable'])} を読む。"
        else:
            title = f"feat: {label}の開発版の指定（dev）を外す"
            first = f"- カタログの {dataset} から `dev` を外す。"
        return {
            "changed": True,
            "branch": f"data/remove-dev/{dataset}",
            "title": title,
            "body": [first, f"- 外す前の `dev`: {describe(root, before['dev'])}"],
        }

    tag = tag or latest_tag(dataset, target)
    parsed = parse_tag(tag)
    if parsed is None or parsed[0] != dataset:
        raise PeakDataError(f"{dataset} の Release のタグの形ではありません: {tag}")
    if target == "stable" and parsed[1] != "stable":
        raise PeakDataError(f"stable には正式版（{dataset}-…）だけを使えます: {tag}")

    data, info = release_manifest(tag)
    if target == "stable" and info.get("prerelease"):
        raise PeakDataError(f"{tag} はプレリリースなので stable には使えません")
    manifest = validate_manifest(data, tag, target)

    path = manifest_path(root, tag)
    if path.exists() and path.read_bytes() != data:
        raise PeakDataError(f"{path.relative_to(root)} はもうあり、Release の manifest.json と中身が違います。"
                            "一度置いたファイルは書き換えません。")
    entry = entry_for(tag, data)
    pointer = read_pointer(root) if dataset == APP_DATASET and target == "stable" else None
    changed = channels.get(target) != entry or (pointer is not None and pointer.get("stable") != entry)
    if target == "stable" and remove_dev and "dev" in channels:
        del channels["dev"]
        changed = True
    if not changed:
        return {"changed": False, "message": f"catalog.json の {dataset} の {target} はもう {tag} を指しています。何もしません。"}

    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)
    channels[target] = entry
    write_catalog(root, catalog)
    if pointer is not None:
        validate_pointer(pointer)
        pointer["stable"] = entry
        write_pointer(root, pointer)

    old_tag = before.get(target)
    old_count = point_count(json.loads(local_manifest(root, old_tag))) if old_tag else None
    new_count = point_count(manifest)
    since = f"{describe(root, old_tag)} から " if old_tag else ""
    if dataset == APP_DATASET and target == "stable":
        title = f"feat: 配布用の山データを {tag} に差し替える"
        lines.append(f"- 配布用のビルドが読む山データ（`stable`）を {since}`{tag}`（{new_count:,} 件）に差し替える。")
        if pointer is not None:
            lines.append("- 0.6.0・0.7.0 が読む参照先ファイル（`current.json`）の `stable` も同じ manifest にする。")
    elif dataset == APP_DATASET:
        title = f"feat: 開発用の山データ（dev）を {tag} に向ける"
        lines.append(f"- 開発用のビルドが読む山データ（`dev`）を {since}`{tag}`（{new_count:,} 件）に向ける。"
                     "配布用（`stable`）は変えない。")
    else:
        title = f"feat: カタログの{label}の {target} を {tag} にする"
        lines.append(f"- カタログの {dataset} の `{target}` を {since}`{tag}`（{new_count:,} 件）にする。"
                     "0.8.0 以降のアプリで、設定の「山データ」から選べる。")
    if old_count is not None:
        diff = new_count - old_count
        lines.append(f"- 件数の差: {diff:+,} 件（{old_count:,} → {new_count:,}）。")
    if target == "stable" and remove_dev and "dev" in before:
        lines.append(f"- 開発用の指定（`dev`、{describe(root, before['dev'])}）も外す。")
    lines.append(f"- 形式: schemaVersion {manifest.get('schemaVersion')}、dataSchemaVersion {manifest.get('dataSchemaVersion')}。")
    lines.append(f"- Release: {info.get('html_url', '')}")
    if "要確認" in (info.get("body") or ""):
        lines.append("- **Release の本文に「要確認」があります。** 理由を読んでから決めてください。")
    lines.append(f"- `{manifests_dir(dataset).as_posix()}/{tag}.json` に Release の manifest.json を書き換えずにコピーした。")
    return {"changed": True, "branch": f"data/{target}/{tag}", "title": title, "body": lines}


# ---- check ---------------------------------------------------------------

def git(*args):
    return subprocess.run(["git", *args], cwd=ROOT, check=True, capture_output=True, text=True).stdout


def manifest_changes(name_status):
    """git diff --name-status の出力から、各データの manifests/ に追加されたファイルと、それ以外の変更を分ける。"""
    added, others = [], []
    pattern = re.compile(rf"{re.escape(SITE_DATA_DIR.as_posix())}/[^/]+/{MANIFESTS_NAME}/")
    for line in name_status.splitlines():
        if not line.strip():
            continue
        status, *paths = line.split("\t")
        if not any(pattern.match(p) for p in paths):
            continue
        if status == "A":
            added.append(paths[0])
        else:
            others.append(f"{status} {' '.join(paths)}")
    return added, others


def base_stable_count(base):
    """base の時点の osm-peaks の stable の件数。カタログが無ければ参照先ファイルから読む。どちらも無ければ None。"""
    try:
        tag = validate_catalog(json.loads(git("show", f"{base}:{(SITE_DATA_DIR / CATALOG_NAME).as_posix()}")))[APP_DATASET]["stable"]
    except subprocess.CalledProcessError:
        try:
            # カタログより前の current.json には dev もあったので、stable だけを読む。
            pointer = json.loads(git("show", f"{base}:{(SITE_DATA_DIR / POINTER_DATASET / POINTER_NAME).as_posix()}"))
            tag = validate_entry(pointer.get("stable"), POINTER_DATASET, "stable", "base の current.json")
        except subprocess.CalledProcessError:
            return None
    return point_count(json.loads(git("show", f"{base}:{manifests_dir(APP_DATASET).as_posix()}/{tag}.json")))


def check(root, base, allow_count_drop):
    problems = []
    notes = []

    def attempt(func, *args):
        try:
            return func(*args)
        except PeakDataError as e:
            problems.append(str(e))
            return None

    catalog = read_catalog(root)
    tags = validate_catalog(catalog)

    for dataset, channels in tags.items():
        for channel, tag in channels.items():
            data = attempt(local_manifest, root, tag)
            if data is None:
                continue
            if sha256(data) != catalog["datasets"][dataset][channel]["manifestSha256"]:
                problems.append(f"catalog.json の {dataset} の {channel} の manifestSha256 が {tag}.json の SHA-256 と合いません")
            manifest = attempt(validate_manifest, data, tag, channel)
            if manifest is None:
                continue
            if dataset != APP_DATASET:
                # アプリがまだ読まないデータは、manifest を確かめるだけにする。
                notes.append(f"{dataset} の {channel}: {tag}（manifest では {point_count(manifest):,} 件）")
                continue
            count = attempt(check_data, manifest, tag)
            if count is not None:
                notes.append(f"{dataset} の {channel}: {tag}（manifest では {point_count(manifest):,} 件、データ本体の配列は {count:,} 件）")

    pointer = read_pointer(root)
    if pointer is not None:
        attempt(validate_pointer, pointer)
        if pointer.get("stable") != catalog["datasets"][APP_DATASET]["stable"]:
            problems.append(f"current.json の stable が catalog.json の {APP_DATASET} の stable と違います")

    added, others = manifest_changes(git("diff", "--name-status", f"{base}...HEAD", "--", SITE_DATA_DIR.as_posix()))
    for change in others:
        problems.append(f"manifests/ の既存のファイルは書き換え・削除できません（追加だけ）: {change}")
    for path in added:
        tag = Path(path).stem
        parsed = parse_tag(tag)
        if parsed is None or Path(path).parent != manifests_dir(parsed[0]):
            problems.append(f"manifests/ に、そのデータの Release のタグの形ではない名前のファイルがあります: {path}")
            continue
        copy = (root / path).read_bytes()
        original = attempt(release_manifest, tag)
        if original is not None and original[0] != copy:
            problems.append(f"{path} が Release {tag} の manifest.json と 1 バイト以上違います")

    old = base_stable_count(base)
    if old is None:
        notes.append("main にカタログも current.json もまだ無いので、件数の比較はしません。")
    else:
        new = point_count(json.loads(local_manifest(root, tags[APP_DATASET]["stable"])))
        drop = count_drop(old, new)
        if drop > MAX_COUNT_DROP:
            message = (f"stable の件数が {drop:.1%} 減っています（{old:,} → {new:,}）。"
                       f"意図した減少なら、PR にラベル「{COUNT_DROP_LABEL}」を付けてください。")
            (notes if allow_count_drop else problems).append(message)

    for note in notes:
        print(f"確認: {note}")
    if problems:
        for problem in problems:
            print(f"::error::{problem}")
        raise PeakDataError(f"{len(problems)} 件の問題があります")
    print("山データのカタログと参照先ファイルはルールどおりです。")


# ---- verify-live / watch -------------------------------------------------

def verify_live(root, attempts, interval):
    """公開中のカタログ・参照先ファイルと、それらが指す manifest が git の内容と同じになるまで、何度か取り直す。"""
    catalog_bytes = catalog_path(root).read_bytes()
    expected = {f"{PAGES_DATA_BASE}{CATALOG_NAME}": catalog_bytes}
    tags = [tag for channels in validate_catalog(json.loads(catalog_bytes)).values() for tag in channels.values()]
    pointer = read_pointer(root)
    if pointer is not None:
        expected[f"{PAGES_DATA_BASE}{POINTER_DATASET}/{POINTER_NAME}"] = pointer_path(root).read_bytes()
        tags.append(validate_pointer(pointer))
    for tag in tags:
        expected[f"{manifest_url_prefix(parse_tag(tag)[0])}{tag}.json"] = local_manifest(root, tag)
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
            print("公開中のカタログ・参照先ファイル・manifest は git と同じです。")
            return
        if attempt + 1 < attempts:
            time.sleep(interval)
    for message in mismatched:
        print(f"::error::{message}")
    raise PeakDataError("公開中の内容が git と合いません")


def watch():
    """公開中のカタログから、manifest とデータ本体までたどれるかを確かめる。
    アプリがまだ読まないデータは manifest まで。参照先ファイルは、カタログの osm-peaks の stable と同じかを見る。"""
    problems = []
    now = int(time.time())
    catalog = json.loads(http_get(f"{PAGES_DATA_BASE}{CATALOG_NAME}?t={now}"))
    tags = validate_catalog(catalog)
    for dataset, channels in tags.items():
        for channel, tag in channels.items():
            entry = catalog["datasets"][dataset][channel]
            try:
                data = http_get(entry["manifestUrl"])
                if sha256(data) != entry["manifestSha256"]:
                    raise PeakDataError(f"公開中の {tag}.json の SHA-256 が catalog.json と合いません")
                manifest = validate_manifest(data, tag, channel)
                if dataset == APP_DATASET:
                    count = check_data(manifest, tag)
                    print(f"確認: {dataset} の {channel}: {tag}（{count:,} 件）を取得できました。")
                else:
                    print(f"確認: {dataset} の {channel}: {tag} の manifest を取得できました。")
            except PeakDataError as e:
                problems.append(f"{dataset} の {channel}: {e}")
    try:
        pointer = json.loads(http_get(f"{PAGES_DATA_BASE}{POINTER_DATASET}/{POINTER_NAME}?t={now}"))
        validate_pointer(pointer)
        if pointer["stable"] != catalog["datasets"][APP_DATASET]["stable"]:
            raise PeakDataError(f"stable が catalog.json の {APP_DATASET} の stable と違います")
        print("確認: current.json の stable は、カタログの osm-peaks の stable と同じです。")
    except PeakDataError as e:
        problems.append(f"current.json: {e}")
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
            "- 「Build」の core のテストが、カタログの osm-peaks の manifest をアプリの読み込み処理で読めることを確かめる。",
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
    p.add_argument("--dataset", choices=DATASETS, default=APP_DATASET)
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
            write_outputs(swap(ROOT, args.dataset, args.target, args.tag.strip(), args.remove_dev))
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
