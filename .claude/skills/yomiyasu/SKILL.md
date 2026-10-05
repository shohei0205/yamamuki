---
name: yomiyasu
description: AIが生成した不自然な日本語を、人間が読みやすく情報密度の高い自然な文章へ書き直す。PR の説明や README、アプリの画面に出す文言など、日本語の文章を推敲するとき、「この文章を読みやすくして」「AI臭さを消して」と頼まれたときに使う。
---

# 日本語の文章を推敲する（yomiyasu）

手順の本体は Codex と共通で、[.agents/skills/yomiyasu/SKILL.md](../../../.agents/skills/yomiyasu/SKILL.md) にある。そのファイルを読み、書かれた手順に従う。手順の中の「スキル配置ディレクトリ」は `.agents/skills/yomiyasu` と読み替える。

このリポジトリの書き方（英単語の前後の半角空白、箇条書き）は AGENTS.md の「言葉づかい」を優先する。

このファイルは、Claude Code にスキルを見つけさせるための入口。`.agents/skills/yomiyasu/` の中身は本家からのコピーなので直接直さない（更新の手順は `.agents/skills/yomiyasu/README.md`）。
