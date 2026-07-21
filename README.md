# 紬希 (Tsumugi)
ver0.0.0

## ビルド

```bash
mvn clean package
```

`target/tsumugi.jar` に依存関係込みの実行可能jarが生成されます
（maven-shade-pluginでfat jar化）。

## 実行

プロジェクトルートに `.env` を用意してください（`AppConfig.java`参照）。

```
DISCORD_TOKEN=...
LM_STUDIO_BASE_URL=http://localhost:1234
LM_STUDIO_CHAT_MODEL=...
LM_STUDIO_EMBEDDING_MODEL=...
TSUMUGI_DB_PATH=data/tsumugi.db
SQLITE_VEC_EXTENSION_PATH=/path/to/vec0.so
```

```bash
java -jar target/tsumugi.jar
```

## 依存関係

- gson / sqlite-jdbc / okhttp / JDA (Discord) / dotenv-java / slf4j-jdk14

sqlite-vec拡張（`vec0`）はMaven依存には含まれません。OS別のネイティブライブラリを
別途配置し、`.env` の `SQLITE_VEC_EXTENSION_PATH` で指定してください。
ロードに失敗した場合はキーワード検索へ自動フォールバックします
（`SqliteConnectionFactory`参照）。

## 法務対応（データ主体の権利）

`tsumugi.memory.rights.DataSubjectRightsService` が以下に対応します。

- 閲覧権・データエクスポート権（利用規約第10条・第13条）: `exportUserData(userId)`
- 削除権・忘れられる権利（利用規約第12条・AI利用者権利章典第4条）: `forgetUser(userId)`
  - Discord上で「忘れて」と送ると確認プロンプトが出て、「本当に忘れて」で確定削除されます
    (`DiscordAdapter`参照)。

修正権（第11条）は現状 `EvidenceRepository.deleteById` + 再登録での運用を想定した
土台のみで、個別更新APIは未実装です。

## 注意（このリポジトリの生成経緯）

このMavenプロジェクト構成は、既存の紬希コードベースに対して法務文書
（利用規約・プライバシーポリシー・AI利用ポリシー等）で定められたデータ主体の権利に
対応するリポジトリ層メソッドを追加した上で、コンパイル可能なディレクトリ構成に
組み直したものです。ビルド検証はネットワーク制限のある環境で行ったため
Maven Centralから依存関係を取得できず、実際の `mvn package` 実行結果は
未確認です。通常のネットワーク環境で一度ビルドを確認してください。
