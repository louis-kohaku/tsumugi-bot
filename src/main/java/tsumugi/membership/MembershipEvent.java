package tsumugi.membership;

import java.time.Instant;
import java.util.UUID;

/**
 * サーバーへの入室/退室イベント1件を表す追記型ログレコード。
 * 「現在在籍中かどうか」はこのイベント列の最新レコードから都度判定する方針とし、
 * 別途の状態キャッシュテーブルは持たない（シンプルさを優先）。
 *
 * 運用は単一ギルド前提のため、guildIdは持たずuserIdのみをキーとする。
 * （複数ギルド運用を始める場合は、このクラスにguildIdを追加し、
 *   MembershipRepositoryのシグネチャにguildIdを足す形で拡張する）
 */
public final class MembershipEvent {

    public enum EventType { ENTER, LEAVE }

    public String id;
    public long userId;
    public EventType eventType;
    public Instant occurredAt;

    /** LEAVEの場合のみ意味を持つ。withdrawalフロー（退会手続き）を経た退室かどうか。 */
    public boolean viaWithdrawal;

    public MembershipEvent() {}

    public MembershipEvent(long userId, EventType eventType, boolean viaWithdrawal) {
        this.id = UUID.randomUUID().toString();
        this.userId = userId;
        this.eventType = eventType;
        this.occurredAt = Instant.now();
        this.viaWithdrawal = viaWithdrawal;
    }
}
