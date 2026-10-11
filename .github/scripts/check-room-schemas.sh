#!/usr/bin/env bash
# Room が書き出した DB のスキーマ(android/app/schemas/)を確かめる。:app をビルドしたあとに動かす。
#   1. ビルドで書き出したスキーマがコミット済みのものと同じか(テーブルを変えたのにスキーマを入れ忘れていないか)
#   2. 比べる元(引数。PR の向き先のブランチ)から、既にある版のスキーマを書き換えたり消したりしていないか
#      (テーブルを変えたのに DB の版を上げ忘れていないか)
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

dir=android/app/schemas
status=0

changed=$(git status --porcelain -- "$dir")
if [ -n "$changed" ]; then
  echo "ビルドで書き出したスキーマが、コミットされたものと違います。生成された $dir/ の変更をコミットしてください。"
  echo "$changed"
  status=1
fi

base="${1:-}"
if [ -n "$base" ]; then
  edited=$(git diff --name-only --diff-filter=MDR "$base" HEAD -- "$dir")
  if [ -n "$edited" ]; then
    echo "既にある版のスキーマが書き換えられています。テーブルを変えたときは DB の版(MountainDatabase.VERSION)を上げ、移行を入れてください。"
    echo "$edited"
    status=1
  fi
fi

if [ "$status" -eq 0 ]; then
  echo "DB のスキーマはルールどおりです。"
fi
exit "$status"
