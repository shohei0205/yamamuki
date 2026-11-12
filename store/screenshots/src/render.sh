#!/bin/bash
# SPDX-FileCopyrightText: 2026 The yamamuki Authors
# SPDX-License-Identifier: MIT

# ストア用のスクリーンショットを panel.html から書き出す（Mac の Google Chrome を使う）。
# 使い方: store/screenshots/src/render.sh
#   raw/ の素材やコピー（panel.html の PANELS）を変えたら、動かし直す。
set -euo pipefail

SRC="$(cd "$(dirname "$0")" && pwd)"
OUT="$(dirname "$SRC")"
CHROME="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"

shot() { # 出力ファイル 幅 高さ クエリ
  mkdir -p "$(dirname "$1")"
  "$CHROME" --headless=new --disable-gpu --hide-scrollbars --force-device-scale-factor=1 \
    --window-size="$2,$3" --virtual-time-budget=3000 \
    --screenshot="$1" "file://$SRC/panel.html?$4&w=$2&h=$3" >/dev/null 2>&1
  # ストアは透過の無い画像を求めるので、アルファを落とす
  sips -s format png "$1" --out "$1" >/dev/null
  echo "$1"
}

for n in 1 2 3 4 5; do
  # App Store: iPhone 6.9 インチ (1320x2868)
  shot "$OUT/app-store/iphone-6.9/0$n.png" 1320 2868 "n=$n&store=ios"
  # Google Play: スマートフォン (1080x1920)
  shot "$OUT/google-play/phone/0$n.png" 1080 1920 "n=$n&store=android"
done
# Google Play: フィーチャーグラフィック (1024x500)
shot "$OUT/google-play/feature-graphic.png" 1024 500 "n=1&mode=feature"
