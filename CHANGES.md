# 強制退会機能 追加パッチ

## 概要

管理者が対象者を選んで強制的に退会させる機能を、新規パッケージ
`tsumugi.forcedwithdrawal` として追加しました。既存の本人発 `tsumugi.withdrawal`
パッケージには一切手を加えていません（責務が異なるため独立させています）。

## フロー

```
🌼｜強制退会（常設・管理者ロール＋Botのみ閲覧可）
  → 管理者が対象者の表示名（部分一致）を投稿
  → 該当メンバーを番号付きで一覧表示
  → 管理者が番号を返信して対象を確定
  → 🌼｜強制退会手続き-対象者名（一時・管理者ロール＋Botのみ閲覧可、対象本人には非表示）を作成
      1. 理由を入力
      2. 「対象: ○○ / 理由: △△ → よろしいですか？(はい/いいえ)」で確認
         「はい」→ 確定。対象者の「お知らせ部屋」に理由付きで通知を送信し、
                   24時間後の実行をスケジュール。手続きチャンネルは削除。
         「いいえ」→ 何も保存せず破棄。手続きチャンネルは削除。
  → 24時間後（自動実行）:
      - Evidenceを匿名化して保存（研究データとして残す）
      - 会話履歴・Evidence・UserModelなど元データを削除（forgetUser）
      - 紬希の庭チャンネル群を削除（存在すれば）
      - サーバーに在籍していればkick／既に自主退出済みならkickのみスキップ
        （データ処理・庭のクリーンアップは在籍有無に関わらず必ず実行）
      - 管理者ログチャンネルに結果を通知
```

データの扱いは常に「匿名化して保存」で固定です（本人発の退会フローの選択肢1と同じ処理）。
利用規約への同意状況（`InitialSetupState`）は問わず、同じ処理を行います。

## 追加ファイル

```
src/main/java/tsumugi/forcedwithdrawal/
├── ForcedWithdrawalState.java              状態enum（NOT_STARTED〜EXECUTED/CANCELLED）
├── ForcedWithdrawalChannelService.java     チャンネルの命名規則・権限設定
├── ForcedWithdrawalService.java            中核ロジック（対象検索・理由確認・24時間後実行）
├── ForcedWithdrawalManager.java            組み立て・エントリーポイント（Facade）
├── ForcedWithdrawalListener.java           JDAイベントの薄い受け口
├── model/
│   └── ForcedWithdrawalRecord.java         永続化レコード
└── store/
    ├── ForcedWithdrawalRepository.java     永続化インタフェース
    └── sqlite/
        └── SqliteForcedWithdrawalRepository.java  SQLite実装（forced_withdrawalテーブル、自前でマイグレーション）
```

## 今回の追加分: `TsumugiApplication.java`（丸ごと置き換え）

前回のパッチは新規ファイルのみで、既存の `TsumugiApplication.java` への配線は
手動追記が必要でした。「強制退会チャンネルが起動できていない」件の原因はこれです
（`ForcedWithdrawalManager.bootstrapGuilds(jda)` が呼ばれていないと
`🌼｜強制退会` チャンネル自体が作成されません）。

今回のzipには、配線済みの `src/main/java/tsumugi/app/TsumugiApplication.java` を
**まるごと置き換え用として同梱**しています。差分適用ではなく、既存ファイルを
このファイルでそのまま上書きしてください。

変更点は以下の3箇所のみです（他のロジックは元のファイルと同一）:

1. `import` に `ForcedWithdrawalListener` / `ForcedWithdrawalManager` を追加
2. お知らせ配信機能のセットアップの後に、強制退会機能のセットアップを追加
   （`initialSetupManager.getChannelService()` / `evidenceRepository` /
   `anonymizedDataRepository` / `dataSubjectRightsService` を共有）
3. 以下の3箇所に `forcedWithdrawalManager` がらみの呼び出しを追加
   - `DiscordAdapter.start(...)` のリスナー一覧に `forcedWithdrawalListener` を追加
   - `forcedWithdrawalManager.bootstrapGuilds(jda);`（← これが無いとチャンネルが作られない）
   - シャットダウンフック・Discordトークン未設定時の早期returnの両方に
     `forcedWithdrawalManager.shutdown();` を追加

## データベース

新規テーブル `forced_withdrawal`（共有DB側）を、`SqliteForcedWithdrawalRepository`
が起動時に自動作成します。既存テーブルへのマイグレーションは不要です。

| カラム | 内容 |
|---|---|
| id | UUID |
| target_user_id / guild_id | 対象者・ギルド |
| admin_user_id | 実行した管理者 |
| target_display_name | 確定時点の表示名 |
| reason | 入力された理由 |
| state | WAITING_CONFIRM→CONFIRMED→EXECUTED / CANCELLED |
| confirmed_at / execute_at / executed_at | 確定日時／実行予定日時／実行日時 |

`CONFIRMED` のまま24時間経過前にBotが再起動しても、`resumePendingSchedules()`
（`bootstrapGuilds()`内で呼び出し）が該当レコードを読み込み、`execute_at` を
基準に再スケジュールします。

## 権限まわりの注意

- `🌼｜強制退会`・`🌼｜強制退会手続き-*` の両チャンネルとも、閲覧可能なのは
  `ADMINISTRATOR` 権限を持つロールのみです。モデレーター等、Administrator権限を
  持たない専用ロールで運用したい場合は `ForcedWithdrawalChannelService` 内の
  `guild.getRoles().stream().filter(role -> role.hasPermission(Permission.ADMINISTRATOR))`
  を、対象ロールIDや別の判定条件に差し替えてください（`InitialSetupChannelService`の
  管理者ログチャンネルと同じ判定ロジックを流用しています）。
- 対象者本人はどちらのチャンネルも閲覧できません。通知は確定後に「お知らせ部屋」
  （`InitialSetupRecord.announceChannelId`）へ直接送信します。お知らせ部屋がまだ
  無い（初期設定未完了）場合は通知をスキップし、ログにその旨を記録します。

## 未対応・today's scope外

- 24時間の間に管理者が取り消す機能（例: 管理者ログでの「取消 <userId>」コマンド）
  は未実装です。必要であれば `ForcedWithdrawalService` に
  `cancelPending(long targetUserId)` を追加し、スケジュールされた
  `ScheduledFuture` を保持してキャンセルする形で拡張できます。
- 対象者名の検索は現状「表示名の部分一致・大文字小文字無視」のみです。IDでの直接
  指定などが必要であれば `searchCandidates()` に分岐を追加してください。
