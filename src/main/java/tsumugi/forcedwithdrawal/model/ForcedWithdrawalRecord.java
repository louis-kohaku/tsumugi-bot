package tsumugi.forcedwithdrawal.model;

import tsumugi.forcedwithdrawal.ForcedWithdrawalState;

import java.time.Instant;

/**
 * 1件の強制退会手続きを表すレコード。
 * 本人発のWithdrawalRecordとは別ドメインのため独立させている。
 */
public final class ForcedWithdrawalRecord {

    public String id;
    public long targetUserId;
    public long guildId;
    public long adminUserId;
    public String targetDisplayName;
    public String reason;
    public ForcedWithdrawalState state;

    public Instant confirmedAt;
    /** この日時にkick＋データ削除を実行する（confirmedAtの24時間後） */
    public Instant executeAt;
    public Instant executedAt;

    public Instant createdAt;
    public Instant updatedAt;

    public ForcedWithdrawalRecord() {}

    public ForcedWithdrawalRecord(String id, long targetUserId, long guildId, long adminUserId,
                                   String targetDisplayName, String reason) {
        this.id = id;
        this.targetUserId = targetUserId;
        this.guildId = guildId;
        this.adminUserId = adminUserId;
        this.targetDisplayName = targetDisplayName;
        this.reason = reason;
        this.state = ForcedWithdrawalState.WAITING_CONFIRM;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public void touch() {
        this.updatedAt = Instant.now();
    }
}
