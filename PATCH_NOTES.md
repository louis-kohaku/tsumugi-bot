# LLM最大トークン数の.env外出し パッチ

## 概要

これまで各クラスにハードコードされていたLLM呼び出しの最大トークン数（max_tokens）を、
`.env` から設定できるように変更しました。

## 変更点まとめ

| 用途 | .envキー | デフォルト値 | 変更前の値の所在 |
|---|---|---|---|
| 通常会話 | `LLM_MAX_TOKENS_CHAT` | 800 | `ConversationEngine.MAX_TOKENS` |
| Evidence抽出 | `LLM_MAX_TOKENS_EVIDENCE` | 800 | `EvidenceExtractor` 呼び出し箇所の直書き値 |
| 日記総評生成 | `LLM_MAX_TOKENS_DIARY` | 600 | `DiarySummaryGenerator.MAX_TOKENS` |
| お知らせ校正 | `LLM_MAX_TOKENS_BROADCAST` | 800 | `BroadcastReviewer.MAX_TOKENS` |

新旧2系統に存在していた `EvidenceExtractor` のmax_tokens不一致（512 vs 800）も、
この変更で単一の設定値（`.env`）に統一されます。

## 適用方法

1. 同梱の `src/main/java/tsumugi/...` 配下のファイルで、既存プロジェクトの同名ファイルを **丸ごと上書き** してください。
   - `src/main/java/tsumugi/app/AppConfig.java`
   - `src/main/java/tsumugi/app/TsumugiApplication.java`
   - `src/main/java/tsumugi/conversation/ConversationEngine.java`
   - `src/main/java/tsumugi/memory/extract/EvidenceExtractor.java`
   - `src/main/java/tsumugi/diary/DiarySummaryGenerator.java`
   - `src/main/java/tsumugi/diary/DiaryManager.java`
   - `src/main/java/tsumugi/broadcast/BroadcastReviewer.java`
   - `src/main/java/tsumugi/broadcast/BroadcastManager.java`

2. `.env`（プロジェクトルート）に以下を追記してください（`.env.example` を参照）。
   ```
   LLM_MAX_TOKENS_CHAT=800
   LLM_MAX_TOKENS_EVIDENCE=800
   LLM_MAX_TOKENS_DIARY=600
   LLM_MAX_TOKENS_BROADCAST=800
   ```
   未設定の場合は自動的にデフォルト値（上表）が使われるため、既存の `.env` に
   何も足さなくても動作は変わりません（後方互換）。

3. `mvn clean package` でビルドし直してください。

## 変更していないもの

- LM Studioとの通信タイムアウト（`readTimeout=60秒`）はこのパッチの対象外です。
  必要であれば別途 `TsumugiApplication.java` の `OkHttpClient.Builder()` 部分を調整してください。
- `IDLE_TTL_SECONDS`（`LmStudioGateway.java`、現状120秒＝コメントは「10分」と矛盾）も
  このパッチでは変更していません。
