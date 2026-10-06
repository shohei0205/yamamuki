#!/usr/bin/env python3
"""画面の文言が文字列リソースにまとまっているかを確かめる。

- Android(android/app/src/main)と iOS(ios/Yamamuki)のコードに、日本語の文字列を直接書いていないか。
  コメント、ログ(Log. / logger.)、行末に「// 文言チェック対象外」と書いた行は除く。
- iOS のコードが引くキーが Localizable.xcstrings にあり、使われていないキーが無いか。
  (Android は R.string のキーをビルドで確かめるので、ここでは見ない)
- Android の strings.xml と iOS の Localizable.xcstrings で、キーがそろっているか。
  片方の OS だけにある文言は、下の PLATFORM_ONLY に理由を添えて書く。
"""
import json
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(subprocess.run(["git", "rev-parse", "--show-toplevel"], capture_output=True, text=True, check=True).stdout.strip())
ANDROID_SOURCES = ROOT / "android/app/src/main"
IOS_SOURCES = ROOT / "ios/Yamamuki"
STRINGS_XML = ANDROID_SOURCES / "res/values/strings.xml"
XCSTRINGS = IOS_SOURCES / "Localizable.xcstrings"
IGNORE_MARK = "文言チェック対象外"

PLATFORM_ONLY = {
    # アプリ名。iOS は ios/project.yml の CFBundleDisplayName。
    "app_name": "android",
    # 事前ダウンロードの画面で、iOS は地方ごとの見出しだけを出す。
    "area_section_choose": "android",
    # iOS は一度断られるとアプリから許可の画面を出せないので、設定アプリへ案内する。
    "dial_permission_denied_message": "ios",
    "dial_permission_open_settings": "ios",
}

JAPANESE = re.compile(r"[぀-ヿ㐀-鿿＀-￯]")
STRING_LITERAL = re.compile(r'"(?:[^"\\]|\\.)*"')
KEY_LITERAL = re.compile(r'"([a-z][a-z0-9]*(?:_[a-z0-9]+)+)"')

errors = []


def error(path, line, message):
    rel = path.relative_to(ROOT)
    errors.append(f"::error file={rel},line={line}::{message}")


def code_part(line):
    """行のうち、コメントを除いたコード。文字列の中の // はコメントとみなさない。"""
    out = []
    i = 0
    while i < len(line):
        m = STRING_LITERAL.match(line, i)
        if m:
            out.append(m.group(0))
            i = m.end()
        elif line.startswith("//", i):
            break
        else:
            out.append(line[i])
            i += 1
    return "".join(out)


def check_hardcoded(root, suffix):
    in_block_comment = False
    for path in sorted(root.rglob(f"*{suffix}")):
        for n, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            line = raw
            if in_block_comment:
                if "*/" not in line:
                    continue
                line = line.split("*/", 1)[1]
                in_block_comment = False
            stripped = line.strip()
            if stripped.startswith("/*") and "*/" not in stripped:
                in_block_comment = True
                continue
            if stripped.startswith("*") or IGNORE_MARK in line:
                continue
            code = code_part(line)
            if re.search(r"\b(Log\.[a-z]|logger\.)", code):
                continue
            for literal in STRING_LITERAL.findall(code):
                if JAPANESE.search(literal):
                    error(path, n, f"画面の文言は文字列リソースに書いてください: {literal}")


def android_keys():
    return set(re.findall(r'<string name="([^"]+)"', STRINGS_XML.read_text(encoding="utf-8")))


def ios_keys():
    return set(json.loads(XCSTRINGS.read_text(encoding="utf-8"))["strings"])


def check_ios_keys(defined):
    used = {}
    for path in sorted(IOS_SOURCES.rglob("*.swift")):
        for n, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            for key in KEY_LITERAL.findall(code_part(line)):
                used.setdefault(key, (path, n))
    for key, (path, n) in sorted(used.items()):
        if key not in defined:
            error(path, n, f"Localizable.xcstrings にキー {key} がありません。")
    for key in sorted(defined - set(used)):
        error(XCSTRINGS, 1, f"キー {key} はどこからも使われていません。")


def check_same_keys(android, ios):
    for key in sorted(android - ios):
        if PLATFORM_ONLY.get(key) != "android":
            error(XCSTRINGS, 1, f"Android の strings.xml にあるキー {key} が iOS にありません。")
    for key in sorted(ios - android):
        if PLATFORM_ONLY.get(key) != "ios":
            error(STRINGS_XML, 1, f"iOS の Localizable.xcstrings にあるキー {key} が Android にありません。")


check_hardcoded(ANDROID_SOURCES / "java", ".kt")
check_hardcoded(IOS_SOURCES, ".swift")
android = android_keys()
ios = ios_keys()
check_ios_keys(ios)
check_same_keys(android, ios)

for e in errors:
    print(e)
sys.exit(1 if errors else 0)
