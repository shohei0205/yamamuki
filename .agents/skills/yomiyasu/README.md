# yomiyasu（コピー）

日本語の文章を推敲するスキル [nanaism/yomiyasu](https://github.com/nanaism/yomiyasu) を、このリポジトリにコピーしたもの。Claude Code と Codex の両方から使う。使い方とこのリポジトリでの注意は AGENTS.md の「言葉づかい」にある。

- 元: nanaism/yomiyasu v2.0.1（コミット `1bc21f9c8e6a55fc4ccd5a2c7dfe78a7b0d814c5`）の `skills/yomiyasu/` と `LICENSE`。`skills/yomiyasu/assets/`（本家の README 用の画像）と `skills/yomiyasu/LICENSE`（ルートの `LICENSE` と同じ）はコピーしない
- ライセンス: MIT（`LICENSE`）。`scripts/yomiyasu_lint.py` には Unicode のデータから作った表が入っており、その分は Unicode License V3（`UNICODE-LICENSE.txt`）。コピーするときは 2 つのライセンスのファイルを消さない。
- 中身は直さない。このリポジトリに合わせたい点は AGENTS.md に書く。
- 検査（`scripts/yomiyasu_check.py`）は、記録を Git の最上位の `.yomiyasu/` に書く。中に自分を無視する `.gitignore` を作るので、コミットには入らない。
- 例外として、`references/` の Markdown には、このリポジトリの決まりに合わせて BOM を付けている（`SKILL.md` には付けない）。

## 版を上げる

本家の新しい版の `skills/yomiyasu/` から `SKILL.md`、`UNICODE-LICENSE.txt`、`references/`、`scripts/` を、ルートから `LICENSE` を上書きし、`references/` の Markdown に BOM を付け直す。上の版とコミットも書き換える。
