package tsumugi.withdrawal.model;

import tsumugi.withdrawal.WithdrawalDataChoice;
import tsumugi.withdrawal.WithdrawalState;

import java.time.Instant;

/**
 * 1ユーザー（1サーバー内）の退会手続き進捗を表すレコード。
 * initialsetup.model.InitialSetupRecordと同様、Discord固有の関心事を含むため
 * 独立したドメインとして扱う。
 */
public final class WithdrawalRecord {

    public long userId;
    public long guildId;
    public WithdrawalState state;

    /** 本人専用に作成された退会チャンネルのID（未作成ならnull） */
    public Long channelId;

    /** 選択されたデータの扱い（未回答ならnull） */
    public WithdrawalDataChoice dataChoice;

    public Instant requestedAt;
    public Instant confirmedAt;

    /** 「削除」選択時、実際にデータが削除された日時（未削除ならnull） */
    public Instant dataDeletedAt;

    public Instant createdAt;
    public Instant updatedAt;

    public WithdrawalRecord() {}

    public WithdrawalRecord(long userId, long guildId) {
        this.userId = userId;
        this.guildId = guildId;
        this.state = WithdrawalState.NOT_REQUESTED;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public void touch() {
        this.updatedAt = Instant.now();
    }
}
