# データの出典とライセンスの画面

設定の「このアプリについて」の「データの出典とライセンス」から開く（Android・iOS）。

- 使っている種類（設定の「山データ」で選んだもの）の山データだけを出す。カードの見出しに種類の名前を出す。
- 取り込んだときに端末へ残した manifest から読むので、オフラインでも見られ、地点データと別の版の出典が混ざらない。残した manifest が取り込み済みの記録の SHA-256 と合わないときは読まない。
- 「山データ」には、manifest のトップレベルの `license`・`attribution`・`sourceUrl` と、リリース日（データを作った日）を出す。
- manifest 版 6 では、出典が `metadata.sources` の配列にまとまっていて、元データと補足を見分ける項目が無い（出典の名前も版ごとに変わり、2026-10-20 の版から「OpenStreetMap（日本・Geofabrik 配布）」など）。そのため「山データ」にはリリース日だけを出し、元データも含めたすべての出典を「出典」の見出しの下に、下の「補足に使った資料」と同じ形で出す。名前（`name`）のある出典が 1 つも無いときは、manifest が読めないときと同じ表示にして、OpenStreetMap のライセンスの表示を消さない。
- 「補足に使った資料」（版 6 は「出典」）には、manifest の `supplementSources`（版 6 は上のとおり `metadata.sources`。タグ・よみがななどの補足に使った Wikipedia などの資料）を 1 件ずつ出す。版 5 は出典 ID の名前順（文字コード順）、版 6 は yamamuki-data が決めた manifest の並び順のまま（並べ替えない）。項目は資料（`source`）・ライセンス（`license`）・著作者（`attribution`）・加工（`changes`）で、空の項目は出さない。値は省略せずに出し、文中の http(s) の URL はタップでブラウザに開けるリンクにする。
- 補足に使った資料の無いデータでは「補足資料なし」と出す。
- 山データをまだ取得していないとき、端末に残した manifest が無いか読めないとき（出典を残す前の版のアプリで取り込んだときなど）は、アプリが持つ種類ごとの表示（「山データ © OpenStreetMap contributors (ODbL)」と OpenStreetMap の著作権のページ、または「山データ 出典：国土地理院 (PDL 1.0)」と公共データ利用規約のページ）を出す。未取得なら「山データ未取得」、manifest が読めなければ「出典情報の取得に失敗」と添える。行は山データが無くてもいつでも押せる。
- 読み込みは `android/core/.../PeakDataCredits.kt` と `ios/YamamukiCore/.../PeakDataCredits.swift`、画面は `CreditsScreen.kt` / `CreditsView.swift` にある。
