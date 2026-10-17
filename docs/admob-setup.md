# AdMob を使うための手順

配布版に Google AdMob のバナー広告を出すために、人が行う作業をまとめる（Issue #109）。アプリへの組み込み方は README の「広告」にある。

## 全体の流れ

1. AdMob のアカウントを作る
2. AdMob にアプリ（Android と iOS）を登録し、バナーの広告ユニットを作る
3. 広告 ID を手元のビルド環境に置く（リポジトリには入れない）
4. app-ads.txt を置く
5. プライバシーポリシーとストアの申告を直す
6. 配布版をビルドして、内部テストで広告を確かめてから公開する

## 1. AdMob のアカウントを作る

- https://admob.google.com/ に Google アカウント（shohei0205@gmail.com など）でログインし、国（日本）・タイムゾーン・通貨（JPY）を選んで登録する。費用はかからない。
- 「お支払い」で次を入れる。
  - 税務情報: 米国外の個人として W-8BEN を画面の質問に答えて出す（日本は米国との租税条約で源泉徴収 0%）。
  - 住所の確認: 収益が約 1,000 円になると PIN が郵送されるので、届いたら入力する。
  - 支払い方法: 日本円を受け取れる銀行口座。8,000 円たまると翌月 21 日ごろに振り込まれる。

## 2. アプリの登録と広告ユニット

Android と iOS で 1 つずつ行う。

- 「アプリ」→「アプリを追加」で、ストアに公開済みかを聞かれる。
  - 公開済みなら、ストアから山むきを検索して選ぶ。
  - まだなら「いいえ」で名前（山むき）だけ登録し、公開後にストアとつなぐ（つなぐまで広告の配信は限られる）。
- 登録すると **アプリ ID**（`ca-app-pub-XXXXXXXXXXXXXXXX~YYYYYYYYYY`、`~` を含む）が出る。
- そのアプリで「広告ユニット」→「バナー」を作る。名前は「方位盤のヘッダー」など。**広告ユニット ID**（`ca-app-pub-XXXXXXXXXXXXXXXX/ZZZZZZZZZZ`、`/` を含む）が出る。
- 審査（アプリの確認）が済むまで、本番の広告はほとんど出ない。

結果として 4 つの ID ができる。

| | アプリ ID | バナーの広告ユニット ID |
|---|---|---|
| Android | `ca-app-pub-…~…` | `ca-app-pub-…/…` |
| iOS | `ca-app-pub-…~…` | `ca-app-pub-…/…` |

## 3. 広告 ID を手元に置く

ID を渡さないと Google のテスト用 ID を使い、「Test Ad」と書かれたテスト広告が出る。本番の ID はリポジトリに入れず、手元のファイルに書く（公開リポジトリに書くと、他人に使われて不正なクリック扱いで停止されるおそれがあるため）。

- Android: `~/.gradle/gradle.properties` に書く。
  ```properties
  admobAppId=ca-app-pub-XXXXXXXXXXXXXXXX~YYYYYYYYYY
  admobBannerId=ca-app-pub-XXXXXXXXXXXXXXXX/ZZZZZZZZZZ
  ```
- iOS: `ios/Ads.local.xcconfig` を作って書く（`.gitignore` 済み）。xcconfig では `//` がコメントになるので、ID の `/` は 1 つのまま書けばよい。
  ```
  ADMOB_APP_ID = ca-app-pub-XXXXXXXXXXXXXXXX~YYYYYYYYYY
  ADMOB_BANNER_ID = ca-app-pub-XXXXXXXXXXXXXXXX/ZZZZZZZZZZ
  ```
- 開発中は本番の ID を使わない。自分の広告を自分で見続けたりタップしたりすると、規約違反でアカウントが止まることがある。AdMob の「設定」→「テストデバイス」に自分の端末を登録しておくと、本番の ID でもテスト広告になる。

## 4. app-ads.txt を置く

- AdMob の「アプリ」→「app-ads.txt」に出る 1 行（`google.com, pub-XXXXXXXXXXXXXXXX, DIRECT, f08c47fec0942fa0`）を、ストアに登録した「デベロッパーのウェブサイト」のドメイン直下に `app-ads.txt` として置く。
- 今の GitHub Pages（`https://shohei0205.github.io/yamamuki/`）はドメイン直下ではないので、次のどれかが要る。
  - リポジトリ `shohei0205/shohei0205.github.io` を作り、直下に `app-ads.txt` を置いて Pages を有効にする（`https://shohei0205.github.io/app-ads.txt` になる）。いちばん手軽。
  - 独自ドメインを使う。
- Google Play Console と App Store Connect のデベロッパーのウェブサイトを `https://shohei0205.github.io/` にする。
- AdMob が確認するまで 24 時間ほどかかる。置かないと広告の収益が大きく減ることがある。

## 5. プライバシーポリシーとストアの申告

広告入りの版を公開する前に済ませる。

- プライバシーポリシー（`site/privacy/index.html`）に、広告のために Google AdMob を使うこと、AdMob が広告 ID・IP アドレス・端末の情報などを集めること、Google のポリシーへのリンク（https://policies.google.com/technologies/partner-sites ）を書き足す。位置情報は広告に渡さないことも書く（まだ書いていない）。
- Google Play Console
  - 「アプリのコンテンツ」→「広告」:「はい、広告が含まれています」。
  - 「広告 ID」: AdMob の SDK が広告 ID の権限（`AD_ID`）を足すので「はい」、目的は「広告またはマーケティング」。
  - 「データセーフティ」: AdMob が集めるデータ（デバイスなどの ID、おおよその位置（IP アドレスから）、アプリの操作、診断）を、Google の案内 https://developers.google.com/admob/android/play-data-disclosure に沿って書き足す。
- App Store Connect
  - 「App のプライバシー」に、AdMob が集めるデータ（ID、使用状況データ、診断など）を書き足す。SDK が持つプライバシーマニフェストの内容が目安になる。
  - アプリはトラッキングの許可（ATT）を求めないので、「トラッキングに使用」は「いいえ」にできる（IDFA を使わない、パーソナライズされない広告が中心になる）。
- 配信する国: 欧州（EEA）・英国・スイスにも配信するなら、Google の同意管理（UMP）で同意画面を出す必要がある。アプリにはまだ入れていないので、当面は配信する国を日本だけにするか、UMP を足す。

## 6. 配布版のビルドと確認

- Android: `~/.gradle/gradle.properties` に ID を書いたうえで、いつもどおり配布版（release）をビルドする。広告が入るのは release だけ。
- iOS: `ios/Ads.local.xcconfig` を置いたうえで、`xcodegen generate` のあと Release でアーカイブする。広告が出るのは Release だけ。
- 内部テスト（Play の内部テスト、TestFlight）で、ヘッダーに広告が出ること、圏外ではヘッダーの絵のままになることを確かめてから公開する。
