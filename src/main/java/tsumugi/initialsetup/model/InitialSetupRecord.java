package tsumugi.initialsetup.model;

import tsumugi.initialsetup.InitialSetupState;

import java.time.Instant;

/**
 * 1ユーザー（1サーバー内）の初期設定進捗を表すレコード。
 *
 * TsumugiModel（会話・記憶ドメイン）とは意図的に分離している。
 * 初期設定はDiscord固有の関心事（ギルド・チャンネル・Kick等）を含むため、
 * 将来スマホアプリ化した際に認証・オンボーディングの概念として
 * 再設計しやすいよう、独立したドメインとして扱う。
 */
public final class InitialSetupRecord {

    public long userId;
    public long guildId;
    public InitialSetupState state;

    /** 入室チャンネルで本人が入力した「読んでほしい名前」 */
    public String displayName;

    /** このユーザー専用に作成された初期設定チャンネルのID（未作成ならnull）。将来の同意フロー用に残す。 */
    public Long setupChannelId;

    /** 「紬希の庭-ユーザー名」カテゴリのID */
    public Long gardenCategoryId;
    public Long chatChannelId;
    public Long logChannelId;
    public Long announceChannelId;

    public Instant createdAt;
    public Instant updatedAt;

    public InitialSetupRecord() {}

    public InitialSetupRecord(long userId, long guildId) {
        this.userId = userId;
        this.guildId = guildId;
        this.state = InitialSetupState.NOT_STARTED;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public void touch() {
        this.updatedAt = Instant.now();
    }
}
