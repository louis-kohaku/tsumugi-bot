package tsumugi.broadcast.model;

import java.time.Instant;
import java.util.UUID;

/**
 * 1回分の配信結果を表す履歴レコード（ログ目的のみ）。
 * 実際に配信した最終文面（校正・往復修正を経たもの）と、最初の原文の両方を保持する。
 */
public final class BroadcastHistoryEntry {

    public String id;
    public long guildId;
    public String originalContent;
    public String finalContent;
    public int successCount;
    public int failureCount;
    public Instant broadcastAt;

    public BroadcastHistoryEntry() {}

    public BroadcastHistoryEntry(long guildId, String originalContent, String finalContent,
                                  int successCount, int failureCount) {
        this.id = UUID.randomUUID().toString();
        this.guildId = guildId;
        this.originalContent = originalContent;
        this.finalContent = finalContent;
        this.successCount = successCount;
        this.failureCount = failureCount;
        this.broadcastAt = Instant.now();
    }
}
