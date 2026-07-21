package tsumugi.withdrawal;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import tsumugi.initialsetup.InitialSetupChannelService;
import tsumugi.memory.rights.DataSubjectRightsService;
import tsumugi.withdrawal.model.WithdrawalRecord;
import tsumugi.withdrawal.store.WithdrawalRepository;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * 退会フローの状態遷移を管理する中核サービス。
 *
 *   「退会」発言 → startWithdrawal() → 専用チャンネル作成 → WAITING_CHOICE
 *   → confirmChoice() で3択回答を受信 → CONFIRMED
 *       → 「削除」選択時: 管理者ログへ通知＋3日後自動削除をスケジュール
 *       → いずれの選択でも: 1分後キックをスケジュール
 *
 * 管理者が手動でデータ削除を確認した場合は markDataManuallyDeleted() で
 * 3日後自動削除のスケジュールをキャンセルする。
 */
public final class WithdrawalService {

    private static final Logger logger = Logger.getLogger(WithdrawalService.class.getName());

    private static final Duration KICK_DELAY = Duration.ofMinutes(1);
    private static final Duration AUTO_DELETE_GRACE_PERIOD = Duration.ofDays(3);

    private final WithdrawalRepository repository;
    private final WithdrawalChannelService channelService;
    private final DataSubjectRightsService dataSubjectRightsService;
    private final InitialSetupChannelService adminNoticeChannelService;

    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "withdrawal-scheduler");
                t.setDaemon(true);
                return t;
            });

    /** userId+guildIdをキーにした保留中スケジュール（再同意等でのキャンセル用に保持） */
    private final Map<String, ScheduledFuture<?>> pendingAutoDeletes = new ConcurrentHashMap<>();

    public WithdrawalService(WithdrawalRepository repository,
                              WithdrawalChannelService channelService,
                              DataSubjectRightsService dataSubjectRightsService,
                              InitialSetupChannelService adminNoticeChannelService) {
        this.repository = repository;
        this.channelService = channelService;
        this.dataSubjectRightsService = dataSubjectRightsService;
        this.adminNoticeChannelService = adminNoticeChannelService;
    }

    /** 「退会」発言を受けたときに呼ぶ。回答待ち中（WAITING_CHOICE）以外は常に新規に手続きを開始する。 */
    public WithdrawalRecord startWithdrawal(Member member) {
        Guild guild = member.getGuild();
        WithdrawalRecord record = repository.load(member.getIdLong(), guild.getIdLong());
        // WAITING_CHOICE（回答待ち中）のみ二重に専用チャンネルを作らないようスキップする。
        // CONFIRMED/KICKED等は、本来サーバーから退出しているはずが再度「退会」と発言できている
        // ＝Kick処理などが何らかの理由で完了していないシステム不良ケースの可能性があるため、
        // ブロックせず再度手続きを開始できるようにする。
        if (record.state == WithdrawalState.WAITING_CHOICE) {
            logger.info("既に退会手続き中（回答待ち）のため再開始をスキップします: userId=" + member.getIdLong());
            return record;
        }

        TextChannel channel = channelService.createWithdrawalChannel(guild, member);
        record.channelId = channel.getIdLong();
        record.requestedAt = java.time.Instant.now();
        transition(record, WithdrawalState.WAITING_CHOICE);
        return record;
    }

    /**
     * 退会チャンネルへの回答（1/2/3）を処理する。
     * WAITING_CHOICE以外の状態、または不正な入力は無視する。
     */
    public void confirmChoice(Member member, String rawInput, TextChannel channel) {
        long userId = member.getIdLong();
        long guildId = member.getGuild().getIdLong();
        WithdrawalRecord record = repository.load(userId, guildId);
        if (record.state != WithdrawalState.WAITING_CHOICE) return;

        WithdrawalDataChoice choice = WithdrawalDataChoice.fromInput(rawInput);
        if (choice == null) {
            channel.sendMessage("1〜3の数字で回答してください。").queue();
            return;
        }

        record.dataChoice = choice;
        record.confirmedAt = java.time.Instant.now();
        transition(record, WithdrawalState.CONFIRMED);

        channel.sendMessage(confirmationMessage(choice)).queue();

        if (choice == WithdrawalDataChoice.DELETE) {
            notifyAdminAboutDeletion(member);
            scheduleAutoDelete(member);
        } else {
            notifyAdminAboutRetention(member, choice);
        }

        scheduleKick(member, channel);
    }

    private String confirmationMessage(WithdrawalDataChoice choice) {
        return switch (choice) {
            case ANONYMIZE -> "承知しました。データは匿名化して保持します。1分後にサーバーから退出となります。今までありがとうございました。";
            case KEEP_NAMED -> "承知しました。データは記名で保持し、次回参加時に引き継げるようにします。1分後にサーバーから退出となります。今までありがとうございました。";
            case DELETE -> "承知しました。データの削除を管理者に依頼します。1分後にサーバーから退出となります。今までありがとうございました。";
        };
    }

    private void notifyAdminAboutDeletion(Member member) {
        adminNoticeChannelService.postAdminLog(member.getGuild(),
                "🗑 退会確定（削除希望）: " + member.getUser().getName()
                        + "（ID: " + member.getIdLong() + "）\n"
                        + "3日以内にデータの削除処理をお願いします。期限を過ぎると自動的に削除されます。");
    }

    private void notifyAdminAboutRetention(Member member, WithdrawalDataChoice choice) {
        String choiceLabel = choice == WithdrawalDataChoice.ANONYMIZE ? "匿名化して保持" : "記名で保持";
        adminNoticeChannelService.postAdminLog(member.getGuild(),
                "🚪 退会確定（" + choiceLabel + "）: " + member.getUser().getName()
                        + "（ID: " + member.getIdLong() + "）");
    }

    private void scheduleKick(Member member, TextChannel channel) {
        long userId = member.getIdLong();
        long guildId = member.getGuild().getIdLong();
        scheduler.schedule(() -> {
            try {
                member.getGuild().kick(member).reason("退会手続き完了").queue(
                        success -> logger.info("退会キックを実行しました: userId=" + userId),
                        failure -> logger.warning("退会キックに失敗しました (userId=" + userId + "): " + failure.getMessage())
                );
            } catch (RuntimeException e) {
                logger.warning("退会キック処理で例外が発生しました (userId=" + userId + "): " + e.getMessage());
            } finally {
                WithdrawalRecord record = repository.load(userId, guildId);
                transition(record, WithdrawalState.KICKED);
                channelService.deleteChannel(channel);
            }
        }, KICK_DELAY.toSeconds(), TimeUnit.SECONDS);
    }

    private void scheduleAutoDelete(Member member) {
        long userId = member.getIdLong();
        long guildId = member.getGuild().getIdLong();
        Guild guild = member.getGuild();
        String key = key(userId, guildId);

        ScheduledFuture<?> future = scheduler.schedule(() -> {
            try {
                WithdrawalRecord record = repository.load(userId, guildId);
                if (record.dataDeletedAt != null) return; // 既に手動削除済み
                dataSubjectRightsService.forgetUser(userId);
                record.dataDeletedAt = java.time.Instant.now();
                repository.save(record);
                adminNoticeChannelService.postAdminLog(guild,
                        "⏰ 期限（3日）を過ぎたため、userId=" + userId + " のデータを自動削除しました。");
                logger.info("退会ユーザーのデータを自動削除しました: userId=" + userId);
            } catch (RuntimeException e) {
                logger.warning("自動削除処理に失敗しました (userId=" + userId + "): " + e.getMessage());
            } finally {
                pendingAutoDeletes.remove(key);
            }
        }, AUTO_DELETE_GRACE_PERIOD.toSeconds(), TimeUnit.SECONDS);

        pendingAutoDeletes.put(key, future);
    }

    /**
     * 管理者が手動でデータ削除を完了させたときに呼ぶ。3日後自動削除のスケジュールをキャンセルする。
     * @return 対象レコードが見つかり処理できた場合true
     */
    public boolean markDataManuallyDeleted(long userId, long guildId) {
        WithdrawalRecord record = repository.load(userId, guildId);
        if (record.state == WithdrawalState.NOT_REQUESTED) return false;

        record.dataDeletedAt = java.time.Instant.now();
        repository.save(record);

        ScheduledFuture<?> future = pendingAutoDeletes.remove(key(userId, guildId));
        if (future != null) future.cancel(false);

        logger.info("管理者による手動削除を確認しました: userId=" + userId);
        return true;
    }

    private String key(long userId, long guildId) {
        return userId + ":" + guildId;
    }

    private void transition(WithdrawalRecord record, WithdrawalState next) {
        WithdrawalState previous = record.state;
        record.state = next;
        repository.save(record);
        logger.info("退会状態を遷移しました: userId=" + record.userId
                + " " + previous + " -> " + next);
    }

    public boolean isWaitingForChoice(long userId, long guildId) {
        return repository.load(userId, guildId).state == WithdrawalState.WAITING_CHOICE;
    }

    public WithdrawalRecord getRecord(long userId, long guildId) {
        return repository.load(userId, guildId);
    }

    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
