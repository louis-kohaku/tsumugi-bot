package tsumugi.initialsetup;

import net.dv8tion.jda.api.entities.Member;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * 不同意ユーザーの猶予期間管理・Kick実行を担うクラス（骨組み）。
 *
 * 実際のKick処理（Member#kick等の呼び出し）は今回実装しない。
 * TODO: guild.kick(member).reason("利用規約未同意") のような実処理を executeKick に実装する。
 * TODO: プロセス再起動をまたいでも猶予期間が復元できるよう、
 *       スケジュールをメモリだけでなくDB（updated_at + 猶予期間）から
 *       起動時に再構築する仕組みを追加する。
 */
public final class KickManager {

    private static final Logger logger = Logger.getLogger(KickManager.class.getName());
    private static final Duration DEFAULT_GRACE_PERIOD = Duration.ofMinutes(10);

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "initialsetup-kick-scheduler");
                t.setDaemon(true);
                return t;
            });

    private final Map<Long, ScheduledFuture<?>> pendingKicks = new ConcurrentHashMap<>();

    /**
     * 猶予期間後にKickを実行するようスケジュールする。
     * 猶予期間中に再同意した場合はcancelScheduledKickを呼ぶこと。
     */
    public void scheduleGracePeriodKick(Member member, Runnable onKickExecuted) {
        scheduleGracePeriodKick(member, DEFAULT_GRACE_PERIOD, onKickExecuted);
    }

    public void scheduleGracePeriodKick(Member member, Duration gracePeriod, Runnable onKickExecuted) {
        long userId = member.getIdLong();
        cancelScheduledKick(userId);

        ScheduledFuture<?> future = scheduler.schedule(() -> {
            try {
                executeKick(member);
            } finally {
                if (onKickExecuted != null) onKickExecuted.run();
                pendingKicks.remove(userId);
            }
        }, gracePeriod.toSeconds(), TimeUnit.SECONDS);

        pendingKicks.put(userId, future);
        logger.info("[TODO実装] Kick猶予期間を開始しました: userId=" + userId
                + " gracePeriod=" + gracePeriod);
    }

    public void cancelScheduledKick(long userId) {
        ScheduledFuture<?> future = pendingKicks.remove(userId);
        if (future != null) {
            future.cancel(false);
            logger.info("Kickスケジュールをキャンセルしました: userId=" + userId);
        }
    }

    /** TODO: 実際のKick処理を実装する。現時点ではログ出力のみ。 */
    public void executeKick(Member member) {
        logger.info("[TODO実装] Kick対象: userId=" + member.getIdLong()
                + " guildId=" + member.getGuild().getIdLong()
                + "（実際のKick処理は未実装）");
        // TODO: member.getGuild().kick(member).reason("利用規約未同意のため").queue();
    }

    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
