#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 The yamamuki Authors
# SPDX-License-Identifier: MIT

# ソースコード(Kotlin・Swift・Python・シェルスクリプト)の先頭に、著作権とライセンスの表示(SPDX の 2 行)があるかを確かめる。
# - 1 行目が #! か // swift-tools-version のファイルは、その次の行から書く
# - .agents/ のスキルは、それぞれのライセンスに従うので対象外
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

failed=0

while IFS= read -r path; do
  case "$path" in
    *.py|*.sh) prefix='#' ;;
    *) prefix='//' ;;
  esac
  expected="$prefix SPDX-FileCopyrightText: 2026 The yamamuki Authors
$prefix SPDX-License-Identifier: MIT"
  head=$(git cat-file blob ":$path" | head -n 3 || true)
  first=${head%%$'\n'*}
  case "$first" in
    '#!'*|'// swift-tools-version'*) head=${head#*$'\n'} ;;
  esac
  if [[ "${head:0:${#expected}}" != "$expected" ]]; then
    echo "::error file=$path::先頭に著作権とライセンスの表示がありません。次の 2 行を入れてください。"$'\n'"$expected"
    failed=1
  fi
done < <(git ls-files -- '*.kt' '*.kts' '*.swift' '*.py' '*.sh' ':!:.agents/**')

if [[ $failed -ne 0 ]]; then
  echo "著作権とライセンスの表示が無いファイルがあります(AGENTS.md の「著作権とライセンスの表示」を参照)。"
  exit 1
fi
echo "著作権とライセンスの表示はすべてのファイルにあります。"
