package tsumugi.withdrawal.model;

import tsumugi.withdrawal.WithdrawalState;

import java.time.Instant;

/**
 * 1ユーザー（1サーバー内）の退会手続き進捗を表すレコード。
 * InitialSetupRecordと対になる存在（入室 ⇔ 退会）。
 */
public final class WithdrawalRecord {

    public long userId;
    public long guildId;
    public WithdrawalState state;

    /** 入室時と同じ表示名。管理者通知や退会専用チャンネル名に使う。 */
    public String displayName;

    /** 本人専用に作成された退会専用チャンネルのID（未作成ならnull） */
    public Long channelId;

    /** 退会専用チャンネルが作成され、選択待ちになった日時 */
    public Instant requestedAt;

    /** この日時を過ぎても未選択・未承認の場合、自動的に通常削除する */
    public Instant deadlineAt;

    public Instant createdAt;
    public Instant updatedAt;

    public WithdrawalRecord() {}

    public WithdrawalRecord(long userId, long guildId) {
        this.userId = userId;
        this.guildId = guildId;
        this.state = WithdrawalState.NOT_STARTED;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public void touch() {
        this.updatedAt = Instant.now();
    }
}
