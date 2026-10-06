#!/usr/bin/env python3
"""画面の文言が文字列リソースにまとまっているかを確かめる。

- Android(android/app/src/main)と iOS(ios/Yamamuki)のコードに、日本語の文字列を直接書いていないか。
  コメント、ログ(Log. / logger.)、行末に「// 文言チェック対象外」と書いた行は除く。
- iOS のコードが引くキーが Localizable.xcstrings にあり、使われていないキーが無いか。
  キーとみなすのは、Strings.text( / Strings.format( の引数と、名前が Keys で終わる配列
  (directionKeys など)の中の文字列。
  (Android は R.string のキーをビルドで確かめるので、ここでは見ない)
- Android の strings.xml と iOS の Localizable.xcstrings で、キーがそろっていて、
  同じキーの文言が同じか(書式指定の違いは除く)。
  片方の OS だけにある文言は、下の PLATFORM_ONLY に理由を添えて書く。
  OS で文言を変えるときは、別のキーにする。
"""
import json
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
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
    # Android は許可の画面が出ないときの案内を添える。iOS は断られたら別の案内
    # (dial_permission_denied_message)を出すので、添えない。
    "dial_permission_message_with_hint": "android",
    "dial_permission_message": "ios",
    # iOS は一度断られるとアプリから許可の画面を出せないので、設定アプリへ案内する。
    "dial_permission_denied_message": "ios",
    "dial_permission_open_settings": "ios",
}

JAPANESE = re.compile(r"[぀-ヿ㐀-鿿＀-￯]")
STRING_LITERAL = re.compile(r'"(?:[^"\\]|\\.)*"')
KEY_LITERAL = re.compile(r'"([a-z][a-z0-9]*(?:_[a-z0-9]+)+)"')
STRINGS_CALL = re.compile(r"\bStrings\.(?:text|format)\(")
KEYS_ARRAY = re.compile(r"\b\w+Keys\s*=\s*\[")
# 書式指定。Android は %1$s・%1$d・%1$,d、iOS は %1$@・%1$ld。比べるときは番号だけにする。
# 型の文字は 1 文字だけ(l・ll・h の長さ指定は除く)にし、すぐ後ろの単位(「%1$dkm」の km)は残す。
FORMAT_SPEC = re.compile(r"%(\d+)\$[-+ #0,.\d]*(?:ll?|h)?[a-zA-Z@]")

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
    for path in sorted(root.rglob(f"*{suffix}")):
        in_block_comment = False
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


def unescape_android(value):
    """strings.xml の \\n・\\uXXXX・\\'・\\" を、画面に出る文字に戻す。"""
    return re.sub(
        r"\\(u[0-9a-fA-F]{4}|.)",
        lambda m: chr(int(m.group(1)[1:], 16)) if m.group(1).startswith("u") and len(m.group(1)) == 5
        else {"n": "\n", "t": "\t"}.get(m.group(1), m.group(1)),
        value,
    )


def android_strings():
    return {
        e.get("name"): unescape_android("".join(e.itertext()))
        for e in ET.parse(STRINGS_XML).getroot().iter("string")
    }


def ios_strings():
    strings = {}
    for key, entry in json.loads(XCSTRINGS.read_text(encoding="utf-8"))["strings"].items():
        value = entry.get("localizations", {}).get("ja", {}).get("stringUnit", {}).get("value")
        if value is None:
            # Xcode の画面で開いたときなどに、訳の無いキーが足されることがある。
            error(XCSTRINGS, 1, f"キー {key} に日本語の文言がありません。")
        strings[key] = value
    return strings


def bracket_end(text, start, open_char, close_char):
    """text[start] の括弧に対応する閉じ括弧の位置。文字列の中の括弧は数えない。"""
    depth = 0
    i = start
    while i < len(text):
        m = STRING_LITERAL.match(text, i)
        if m:
            i = m.end()
            continue
        if text[i] == open_char:
            depth += 1
        elif text[i] == close_char:
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return len(text)


def check_ios_keys(defined):
    used = {}
    for path in sorted(IOS_SOURCES.rglob("*.swift")):
        lines = path.read_text(encoding="utf-8").splitlines()
        text = "\n".join(code_part(line) for line in lines)
        for pattern, open_char, close_char in ((STRINGS_CALL, "(", ")"), (KEYS_ARRAY, "[", "]")):
            for m in pattern.finditer(text):
                start = m.end() - 1
                end = bracket_end(text, start, open_char, close_char)
                for k in KEY_LITERAL.finditer(text, start, end):
                    used.setdefault(k.group(1), (path, text.count("\n", 0, k.start()) + 1))
    for key, (path, n) in sorted(used.items()):
        if key not in defined:
            error(path, n, f"Localizable.xcstrings にキー {key} がありません。")
    for key in sorted(defined - set(used)):
        error(XCSTRINGS, 1, f"キー {key} はどこからも使われていません。")


def check_same_strings(android, ios):
    for key in sorted(set(android) - set(ios)):
        if PLATFORM_ONLY.get(key) != "android":
            error(XCSTRINGS, 1, f"Android の strings.xml にあるキー {key} が iOS にありません。")
    for key in sorted(set(ios) - set(android)):
        if PLATFORM_ONLY.get(key) != "ios":
            error(STRINGS_XML, 1, f"iOS の Localizable.xcstrings にあるキー {key} が Android にありません。")
    for key in sorted(set(android) & set(ios)):
        if PLATFORM_ONLY.get(key):
            error(STRINGS_XML, 1, f"キー {key} は両方の OS にあるので、PLATFORM_ONLY から消してください。")
        if ios[key] is None:
            continue
        a = FORMAT_SPEC.sub(r"%\1", android[key])
        i = FORMAT_SPEC.sub(r"%\1", ios[key])
        if a != i:
            error(STRINGS_XML, 1, f"キー {key} の文言が Android と iOS で違います。OS で変えるなら別のキーにしてください: {a!r} / {i!r}")


check_hardcoded(ANDROID_SOURCES / "java", ".kt")
check_hardcoded(IOS_SOURCES, ".swift")
android = android_strings()
ios = ios_strings()
check_ios_keys(set(ios))
check_same_strings(android, ios)

for e in errors:
    print(e)
sys.exit(1 if errors else 0)
