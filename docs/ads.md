# 広告

配布版では、方位盤のヘッダーの中央に Google AdMob のバナー広告(320×50)を出す。アカウントの作成や app-ads.txt など、人が行う作業は [admob-setup.md](admob-setup.md) にまとめる。

- 開発版には出さない。Android は SDK も入れない(Gradle のプロパティ `ads`。既定の `release` は配布版だけ、`all` は開発版にも、`none` はどちらにも入れない)。iOS は Release だけ出す(ビルド設定 `ADS_ENABLED`)。
- 広告の ID はリポジトリに入れない。無いときは Google のテスト用 ID を使い、「Test Ad」と書かれたテスト広告が出る。
  - Android: `~/.gradle/gradle.properties` に `admobAppId`(アプリ ID)と `admobBannerId`(バナーの広告ユニット ID)を書く。
  - iOS: `ios/Ads.local.xcconfig`(`.gitignore` 済み)に `ADMOB_APP_ID` と `ADMOB_BANNER_ID` を書く。既定の値は `ios/Ads.xcconfig` にある。
- 広告には位置情報を渡さない。トラッキングの許可(iOS の ATT)は求めず、欧州向けの同意画面(UMP)はまだ入れていない。
- 開発中に本番の ID で自分の広告を見続けたりタップしたりしない(AdMob の規約違反になる)。手元の端末をテストデバイスにすると、本番の ID でもテスト広告が出る。
  - Android: `~/.gradle/gradle.properties` の `admobTestDeviceIds` に、端末の ID をカンマ区切りで書く。ID は初回の広告の読み込みで logcat(タグ `Ads`)に出る。
  - iOS: `ios/Ads.local.xcconfig` の `ADMOB_TEST_DEVICE_IDS` に書く。ID は初回の広告の読み込みで Xcode のコンソールに「To get test ads on this device」と出る。AdMob の管理画面での登録は広告 ID(ATT の許可)が要るので、iOS はこちらで指定する。
