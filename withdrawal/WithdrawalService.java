package tsumugi.withdrawal;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import tsumugi.admin.AdminNotificationService;
import tsumugi.core.model.TsumugiModel.Evidence;
import tsumugi.memory.anonymized.AnonymizedDataRepository;
import tsumugi.memory.rights.DataSubjectRightsService;
import tsumugi.memory.store.EvidenceRepository;
import tsumugi.withdrawal.model.WithdrawalRecord;
import tsumugi.withdrawal.store.WithdrawalRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * 退会フローの状態遷移・データ処理を管理する中核サービス。
 * InitialSetupServiceと対になる存在。
 *
 * フロー:
 *   退会チャンネルに「退会」投稿 → startWithdrawal() → 退会専用チャンネル作成・WAITING_CHOICE
 *     → handleChoiceMessage() で1/2/3を受信
 *         1: 匿名化して削除（COMPLETED_ANONYMIZED、即時）
 *         2: 管理者監査待ち（PENDING_ADMIN_REVIEW）→ 管理者承認 or 3日経過で通常削除
 *         3: 保持（COMPLETED_RETAINED、削除しない）
 *   WAITING_CHOICE / PENDING_ADMIN_REVIEW のまま3日経過 → 自動的に通常削除
 */
public final class WithdrawalService {

    private static final Logger logger = Logger.getLogger(WithdrawalService.class.getName());
    private static final Duration DEADLINE = Duration.ofDays(3);

    private final WithdrawalRepository repository;
    private final WithdrawalChannelService channelService;
    private final DataSubjectRightsService dataSubjectRightsService;
    private final EvidenceRepository evidenceRepository;
    private final AnonymizedDataRepository anonymizedDataRepository;
    private final AdminNotificationService adminNotificationService;

    private final ScheduledExecutorService deadlineScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "withdrawal-deadline-scheduler");
                t.setDaemon(true);
                return t;
            });

    /** 期限切れチェック時にチャンネルを探すためJDAを後から注入する（起動順序の都合上）。 */
    private volatile JDA jda;

    public WithdrawalService(WithdrawalRepository repository,
                              WithdrawalChannelService channelService,
                              DataSubjectRightsService dataSubjectRightsService,
                              EvidenceRepository evidenceRepository,
                              AnonymizedDataRepository anonymizedDataRepository,
                              AdminNotificationService adminNotificationService) {
        this.repository = repository;
        this.channelService = channelService;
        this.dataSubjectRightsService = dataSubjectRightsService;
        this.evidenceRepository = evidenceRepository;
        this.anonymizedDataRepository = anonymizedDataRepository;
        this.adminNotificationService = adminNotificationService;
    }

    public void setJda(JDA jda) {
        this.jda = jda;
    }

    /** 退会チャンネルへの「退会」投稿を受けて呼ばれる。 */
    public void startWithdrawal(Member member) {
        Guild guild = member.getGuild();
        long userId = member.getIdLong();
        WithdrawalRecord record = repository.load(userId, guild.getIdLong());

        if (record.state == WithdrawalState.WAITING_CHOICE || record.state == WithdrawalState.PENDING_ADMIN_REVIEW) {
            logger.info("既に退会手続き中のため、新規作成をスキップします: userId=" + userId);
            return;
        }

        String displayName = member.getEffectiveName();
        TextChannel channel = channelService.createDedicatedChannel(guild, member, displayName);

        record.displayName = displayName;
        record.channelId = channel.getIdLong();
        record.requestedAt = Instant.now();
        record.deadlineAt = record.requestedAt.plus(DEADLINE);
        record.state = WithdrawalState.WAITING_CHOICE;
        repository.save(record);

        scheduleDeadlineCheck(record);
        channelService.scheduleEntryChannelRecreate(guild);

        logger.info("退会手続きを開始しました: userId=" + userId + " guildId=" + guild.getIdLong());
    }

    /** 退会専用チャンネルへの投稿（1/2/3の選択）を処理する。 */
    public void handleChoiceMessage(Member member, String rawText, TextChannel channel) {
        long userId = member.getIdLong();
        long guildId = member.getGuild().getIdLong();
        WithdrawalRecord record = repository.load(userId, guildId);
        if (record.state != WithdrawalState.WAITING_CHOICE) return;

        String text = rawText == null ? "" : rawText.strip();

        if (text.equals("1") || text.contains("匿名")) {
            handleAnonymize(member, record, channel);
        } else if (text.equals("2") || text.contains("監査")) {
            handlePendingReview(member, record, channel);
        } else if (text.equals("3") || text.contains("次") || (text.contains("保存") && !text.contains("匿名"))) {
            handleRetain(record, channel);
        } else {
            channelService.postCompletionMessage(channel, "「1」「2」「3」のいずれかの数字で入力してください。");
        }
    }

    private void handleAnonymize(Member member, WithdrawalRecord record, TextChannel channel) {
        long userId = record.userId;
        List<Evidence> evidences = evidenceRepository.loadAll(userId);
        anonymizedDataRepository.saveAnonymized(evidences);
        dataSubjectRightsService.forgetUser(userId);

        record.state = WithdrawalState.COMPLETED_ANONYMIZED;
        repository.save(record);

        channelService.postCompletionMessage(channel,
                "個人を特定できる情報は削除し、傾向データのみ匿名で研究用に保存しました。これまでありがとうございました。");
        channelService.scheduleDedicatedChannelDelete(channel);
        logger.info("退会（匿名保存）が完了しました: userId=" + userId);
    }

    private void handlePendingReview(Member member, WithdrawalRecord record, TextChannel channel) {
        record.state = WithdrawalState.PENDING_ADMIN_REVIEW;
        repository.save(record);

        adminNotificationService.postWithdrawalAuditRequest(member.getGuild(), record.userId, record.displayName);
        channelService.postCompletionMessage(channel,
                "承知しました。管理者の確認をお待ちください。対応が完了次第データが削除されます（3日以内に対応がない場合は自動的に削除されます）。");
        logger.info("退会（管理者監査待ち）を受け付けました: userId=" + record.userId);
    }

    private void handleRetain(WithdrawalRecord record, TextChannel channel) {
        record.state = WithdrawalState.COMPLETED_RETAINED;
        repository.save(record);

        channelService.postCompletionMessage(channel, "データはそのまま保持します。またいつでもお話しできるのをお待ちしています。");
        channelService.scheduleDedicatedChannelDelete(channel);
        logger.info("退会（データ保持）を受け付けました: userId=" + record.userId);
    }

    /** 管理者通知チャンネルでの「承認 &lt;userId&gt;」コマンドを受けて呼ばれる。対応対象があればtrue。 */
    public boolean handleAdminApproval(long userId, Guild guild) {
        WithdrawalRecord record = repository.load(userId, guild.getIdLong());
        if (record.state != WithdrawalState.PENDING_ADMIN_REVIEW) return false;

        dataSubjectRightsService.forgetUser(userId);
        record.state = WithdrawalState.COMPLETED_DELETED;
        repository.save(record);

        adminNotificationService.postWithdrawalResolved(guild, userId, "管理者承認により削除しました");
        notifyDedicatedChannelIfPresent(record, "管理者の確認により、データを削除しました。これまでありがとうございました。");
        logger.info("退会（管理者承認による削除）が完了しました: userId=" + userId);
        return true;
    }

    // ═══════════════════════════════════════
    //  期限管理
    // ═══════════════════════════════════════

    private void scheduleDeadlineCheck(WithdrawalRecord record) {
        Duration delay = Duration.between(Instant.now(), record.deadlineAt);
        long delaySeconds = Math.max(0, delay.toSeconds());
        deadlineScheduler.schedule(
                () -> checkAndForceDeleteIfExpired(record.userId, record.guildId),
                delaySeconds, TimeUnit.SECONDS);
    }

    private void checkAndForceDeleteIfExpired(long userId, long guildId) {
        WithdrawalRecord record = repository.load(userId, guildId);
        if (record.state != WithdrawalState.WAITING_CHOICE && record.state != WithdrawalState.PENDING_ADMIN_REVIEW) {
            return; // 既に確定済み
        }
        if (record.deadlineAt != null && Instant.now().isBefore(record.deadlineAt)) {
            // 再起動直後の再スケジュール等でまだ期限に達していない場合は再度スケジュールし直す
            scheduleDeadlineCheck(record);
            return;
        }

        dataSubjectRightsService.forgetUser(userId);
        record.state = WithdrawalState.COMPLETED_DELETED;
        repository.save(record);

        notifyDedicatedChannelIfPresent(record, "3日以内に対応が確認できなかったため、自動的にデータを削除しました。");
        logger.info("退会（期限切れによる自動削除）を実行しました: userId=" + userId);
    }

    private void notifyDedicatedChannelIfPresent(WithdrawalRecord record, String message) {
        if (jda == null || record.channelId == null) return;
        TextChannel channel = jda.getTextChannelById(record.channelId);
        if (channel == null) return;
        channelService.postCompletionMessage(channel, message);
        channelService.scheduleDedicatedChannelDelete(channel);
    }

    /** 起動時に呼ぶ。再起動をまたいでも未確定の退会手続きの期限監視を再構築する。 */
    public void resumePendingSchedules() {
        for (WithdrawalState state : List.of(WithdrawalState.WAITING_CHOICE, WithdrawalState.PENDING_ADMIN_REVIEW)) {
            for (WithdrawalRecord record : repository.loadByState(state)) {
                if (record.deadlineAt == null) continue;
                scheduleDeadlineCheck(record);
            }
        }
    }

    public void shutdown() {
        deadlineScheduler.shutdown();
        try {
            if (!deadlineScheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                deadlineScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            deadlineScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
