# yamamuki (iOS 版)

Android 版とほぼ同じ機能の iOS アプリ（Swift + SwiftUI、iOS 15 以上）。

構成、開発環境、ビルドと実行の手順、Android 版との違いは、リポジトリ直下の [README](../README.md) にまとめている。

```bash
cd ios
(cd YamamukiCore && swift test)   # core の単体テスト
xcodegen generate                 # Yamamuki.xcodeproj を生成
open Yamamuki.xcodeproj
```
