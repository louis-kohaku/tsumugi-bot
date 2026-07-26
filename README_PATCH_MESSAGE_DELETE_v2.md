# 修正版パッチ（v2）: 入室・退会希望チャンネルの個人的投稿を即時削除

## v1からの変更点（重要）

前回渡した `WithdrawalChannelService.java` は、実際のプロジェクトが使っている
API（`createWithdrawalChannel` / `ensureWithdrawalRequestChannel` / `deleteChannel` /
`isWithdrawalChannelName` / `isWithdrawalRequestChannelName` /
`scheduleWithdrawalRequestChannelRecreate`）とは異なるバージョンのファイルでした。
そのため `WithdrawalManager.java` / `WithdrawalService.java` がシンボルを見つけられず
ビルドエラーになっていました。

このv2では、既存のAPI（メソッド名・シグネチャ）を完全にそのまま維持し、
Bot自身の権限に `Permission.MESSAGE_MANAGE` を追加しただけにしてあります。
`WithdrawalListener.java` / `InitialSetupListener.java` / `InitialSetupChannelService.java`
はv1と同じ内容です（元々正しかったため変更なし）。

## 適用前に：まず重複ファイルを削除してください

`mvn clean compile` のログで「クラスが重複しています」というエラーが出ていたのは、
`WithdrawalChannelService.java` と `WithdrawalListener.java` が
`src/main/java/tsumugi/withdrawal/` 以外の場所にも存在していたためです
（ファイル数が76→78と2つ増えていたのがその証拠です）。

PowerShellで以下を実行し、該当ファイルの場所を確認してください。

```powershell
cd C:\Users\26B2008\Desktop\project\tsumugi-bot
Get-ChildItem -Recurse -Filter "WithdrawalChannelService.java" | Select-Object FullName
Get-ChildItem -Recurse -Filter "WithdrawalListener.java" | Select-Object FullName
```

`src\main\java\tsumugi\withdrawal\` 配下**以外**にあるファイル（プロジェクトルート直下の
`withdrawal\` フォルダなど、`src\main\java` を経由しない場所）を削除してください。
最終的に、それぞれのファイルが `src\main\java\tsumugi\withdrawal\` 配下に1つだけ
残っている状態にしてください。

## 適用方法

重複ファイルを削除したら、このzip内の4ファイルを対応するパスに**上書き**配置してください。

```
src/main/java/tsumugi/initialsetup/InitialSetupListener.java
src/main/java/tsumugi/initialsetup/InitialSetupChannelService.java
src/main/java/tsumugi/withdrawal/WithdrawalListener.java
src/main/java/tsumugi/withdrawal/WithdrawalChannelService.java
```

その後:

```powershell
mvn clean compile
```

エラーが出ないことを確認してから `mvn exec:java` で起動してください。

## 変更内容のおさらい

- 入室チャンネル（🌼｜入室）・退会希望チャンネル（🌼｜退会希望）は @everyone に見える
  常設チャンネルのため、投稿（名前入力・「退会」発言）を処理した直後に
  `event.getMessage().delete()` でその場で即座に削除するようにした。
- 既存の「5秒後にチャンネルごと削除→再作成」処理はそのまま残しており、二重の安全策になっている。
- 削除にはBot自身に `MESSAGE_MANAGE` 権限が必要なため、両チャンネルの作成処理
  （`InitialSetupChannelService.createEntryChannel` /
  `WithdrawalChannelService.createWithdrawalRequestChannel`）でBot自身への権限付与に追加した。
- 同意チャンネル・引継ぎ確認チャンネル・退会専用チャンネル・管理者ログ／通知チャンネルは
  本人・Bot・管理者のみ閲覧可能な一時／専用チャンネルのため、削除対象には含めていない。

## 注意点

- 既に作成済みの入室／退会希望チャンネルは、Botのチャンネル権限がまだ古いままの可能性があります。
  一度チャンネルが再作成される（5秒後リセット処理）か、手動でチャンネル権限を更新するまでは、
  MESSAGE_MANAGE権限が反映されない点にご注意ください。
- 削除に失敗した場合は例外を投げず、`Logger.fine` でログに残すだけにしています。
