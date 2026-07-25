# 変更内容（再入室時の引継ぎ確認：はい→データ引継ぎ／いいえ→匿名化保存）

## ビルドエラーの修正について

`mvn clean compile` で `パッケージtsumugi.memory.anonymizedは存在しません` というエラーが
出ていたのは、`tsumugi.memory.anonymized` パッケージが `src/main/java` 配下にまだ
存在していなかったためです（退会機能の匿名保存で使っていた実装は別ツリーにしかなく、
実際にビルドされる `src/main/java` 側には未追加でした）。
今回のzipには、このパッケージを `src/main/java` 配下に新規追加したものを含めています。

## 含まれるファイル

**新規追加（既存の実装をsrc/main/java配下にコピーしただけで、ロジック変更はありません）**
- src/main/java/tsumugi/memory/anonymized/AnonymizedEvidence.java
- src/main/java/tsumugi/memory/anonymized/AnonymizedDataRepository.java
- src/main/java/tsumugi/memory/anonymized/SqliteAnonymizedDataRepository.java

**既存ファイルの置き換え**
- src/main/java/tsumugi/initialsetup/InitialSetupService.java
- src/main/java/tsumugi/initialsetup/InitialSetupManager.java
- src/main/java/tsumugi/initialsetup/InitialSetupChannelService.java
- src/main/java/tsumugi/app/TsumugiApplication.java

リポジトリの同じパスにこれらを配置・上書きしてください（新規3ファイルは新しいパスへ追加、
既存4ファイルは同名の既存ファイルを上書き）。

## 変更の要点

1. `InitialSetupService`
   - コンストラクタに `WithdrawalRepository` / `DataSubjectRightsService` /
     `EvidenceRepository` / `AnonymizedDataRepository` を追加。
   - `handleRejoinConfirmAnswer()`
     - 「はい」: 今の表示名で `userDbRegistry.renameUser()` を呼び、同じuserIdの
       既存DBフォルダ（前回の会話履歴・Evidence・UserModel）をそのまま使い続ける。
       決着後、withdrawalレコードを削除。
     - 「いいえ」: `evidenceRepository.loadAll()` → `anonymizedDataRepository.saveAnonymized()`
       で匿名化保存したうえで、`dataSubjectRightsService.forgetUser()` で元データを削除。
       決着後、withdrawalレコードを削除し、通常の新規フローへ。

2. `InitialSetupManager`
   - `createDefault()` の引数に上記4つの依存を追加。
   - `handleRejoinConfirmMessage()` で「はい/いいえ」以外の入力時に再入力を促すよう変更。

3. `InitialSetupChannelService`
   - 汎用の `postMessage(TextChannel, String)` を追加（再入力案内などに使用）。

4. `TsumugiApplication`
   - `AnonymizedDataRepository` を早い段階で生成し、`InitialSetupManager.createDefault()`
     へ他の依存とともに渡すよう配線を追加。

## 動作確認のポイント

- 記名保持で退会 → 表示名を変えて再入室 → 「はい」と回答
  → 前回の会話・Evidenceがそのまま参照できること
- 記名保持で退会 → 再入室 → 「いいえ」と回答
  → anonymized_evidenceテーブルに匿名化データが増えていること
  → 元のuserIdのepisodic_events / evidence / user_modelが空になっていること
  → 同じuserIdで再度参加しても、もう引継ぎ確認は出ないこと（withdrawalレコード削除済みのため）
