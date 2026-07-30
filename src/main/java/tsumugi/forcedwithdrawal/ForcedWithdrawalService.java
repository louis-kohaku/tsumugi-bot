package tsumugi.forcedwithdrawal;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import tsumugi.core.model.TsumugiModel.Evidence;
import tsumugi.forcedwithdrawal.model.ForcedWithdrawalRecord;
import tsumugi.forcedwithdrawal.store.ForcedWithdrawalRepository;
import tsumugi.initialsetup.InitialSetupChannelService;
import tsumugi.initialsetup.InitialSetupState;
import tsumugi.initialsetup.model.InitialSetupRecord;
import tsumugi.initialsetup.store.InitialSetupRepository;
import tsumugi.memory.anonymized.AnonymizedDataRepository;
import tsumugi.memory.rights.DataSubjectRightsService;
import tsumugi.memory.store.EvidenceRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * 管理者発の強制退会フローの中核サービス。
 *
 *   強制退会チャンネルに名前投稿 → searchCandidates() → 候補一覧提示
 *   → 番号回答 → selectCandidate() → 手続きチャンネル作成 → WAITING_REASON
 *   → 理由投稿 → WAITING_CONFIRM
 *   → 「はい」 → confirm() → レコード永続化(CONFIRMED) → 対象者へお知らせ部屋で通知
 *       → 24時間後 executeForcedWithdrawal()（匿名化・削除・kick）
 *   → 「いいえ」 → cancel() → 何も永続化せず破棄
 *
 * データの扱いは常に「匿名化して保持」（withdrawalフローの選択肢1と同じ処理）で固定とする。
 * 利用規約への同意状況（InitialSetupState）は問わない。
 *
 * 本人が既に自主退出済みであっても、匿名化・データ削除・庭のクリーンアップは必ず実行する
 * （kickだけスキップする）。
 */
public final class ForcedWithdrawalService {

    private static final Logger logger = Logger.getLogger(ForcedWithdrawalService.class.getName());
    private static final Duration EXECUTE_DELAY = Duration.ofHours(24);

    private static final String NOTIFY_TEMPLATE = """
        紬希運営より

        誠に申し訳ございませんが、以下の理由によりサーバーのご利用を終了させていただくこととなりました。

        理由: %s

        本メッセージから24時間後を目処に退出処理を行います。ご不明な点がございましたら、
        サーバーの管理者までご連絡ください。今までのご利用、ありがとうございました。
        """;

    private final ForcedWithdrawalRepository repository;
    private final ForcedWithdrawalChannelService channelService;
    private final InitialSetupRepository initialSetupRepository;
    private final InitialSetupChannelService initialSetupChannelService;
    private final EvidenceRepository evidenceRepository;
    private final AnonymizedDataRepository anonymizedDataRepository;
    private final DataSubjectRightsService dataSubjectRightsService;

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "forced-withdrawal-scheduler");
                t.setDaemon(true);
                return t;
            });

    /** 管理者ID単位の検索候補（番号選択待ち）。DBには残さない一時状態。 */
    private final Map<Long, List<Long>> pendingCandidatesByAdmin = new ConcurrentHashMap<>();

    /** 手続きチャンネルID単位の進行中セッション（理由入力〜確認）。DBには残さない一時状態。 */
    private final Map<Long, PendingSession> sessionsByChannelId = new ConcurrentHashMap<>();

    private volatile net.dv8tion.jda.api.JDA jda;

    public ForcedWithdrawalService(ForcedWithdrawalRepository repository,
                                    ForcedWithdrawalChannelService channelService,
                                    InitialSetupRepository initialSetupRepository,
                                    InitialSetupChannelService initialSetupChannelService,
                                    EvidenceRepository evidenceRepository,
                                    AnonymizedDataRepository anonymizedDataRepository,
                                    DataSubjectRightsService dataSubjectRightsService) {
        this.repository = repository;
        this.channelService = channelService;
        this.initialSetupRepository = initialSetupRepository;
        this.initialSetupChannelService = initialSetupChannelService;
        this.evidenceRepository = evidenceRepository;
        this.anonymizedDataRepository = anonymizedDataRepository;
        this.dataSubjectRightsService = dataSubjectRightsService;
    }

    public void setJda(net.dv8tion.jda.api.JDA jda) {
        this.jda = jda;
    }

    private enum SessionState { WAITING_REASON, WAITING_CONFIRM }

    private static final class PendingSession {
        final long targetUserId;
        final long guildId;
        final String targetDisplayName;
        final long adminUserId;
        SessionState state = SessionState.WAITING_REASON;
        String reason;

        PendingSession(long targetUserId, long guildId, String targetDisplayName, long adminUserId) {
            this.targetUserId = targetUserId;
            this.guildId = guildId;
            this.targetDisplayName = targetDisplayName;
            this.adminUserId = adminUserId;
        }
    }

    // ═══════════════════════════════════════
    //  対象者検索・選択
    // ═══════════════════════════════════════

    /** 強制退会チャンネルへの投稿を対象者名検索として処理する。 */
    public void searchCandidates(Member admin, Guild guild, String rawQuery, TextChannel channel) {
        String query = rawQuery == null ? "" : rawQuery.strip();
        if (query.isEmpty()) return;

        List<Member> candidates = guild.getMembers().stream()
                .filter(m -> !m.getUser().isBot())
                .filter(m -> m.getEffectiveName().toLowerCase().contains(query.toLowerCase()))
                .limit(10)
                .toList();

        if (candidates.isEmpty()) {
            channelService.postMessage(channel, "「" + query + "」に該当するメンバーが見つかりませんでした。");
            return;
        }

        StringBuilder sb = new StringBuilder("以下の中から対象者を番号で選んでください。\n\n");
        for (int i = 0; i < candidates.size(); i++) {
            Member m = candidates.get(i);
            sb.append(i + 1).append(": ").append(m.getEffectiveName())
                    .append("（ID: ").append(m.getIdLong()).append("）\n");
        }
        channelService.postMessage(channel, sb.toString());

        pendingCandidatesByAdmin.put(admin.getIdLong(),
                candidates.stream().map(Member::getIdLong).toList());
    }

    /** 強制退会チャンネルへの投稿を番号選択として処理する。選択待ちが無ければfalse。 */
    public boolean selectCandidate(Member admin, Guild guild, String rawInput, TextChannel channel) {
        List<Long> candidates = pendingCandidatesByAdmin.get(admin.getIdLong());
        if (candidates == null) return false;

        Integer index = parseIndex(rawInput, candidates.size());
        if (index == null) {
            channelService.postMessage(channel, "1〜" + candidates.size() + "の数字で選んでください。");
            return true;
        }

        pendingCandidatesByAdmin.remove(admin.getIdLong());
        long targetUserId = candidates.get(index);
        Member target = guild.getMemberById(targetUserId);
        if (target == null) {
            channelService.postMessage(channel, "対象者が見つかりませんでした（既に退出した可能性があります）。");
            return true;
        }

        String displayName = target.getEffectiveName();
        TextChannel dedicated = channelService.createDedicatedChannel(guild, displayName);
        sessionsByChannelId.put(dedicated.getIdLong(),
                new PendingSession(targetUserId, guild.getIdLong(), displayName, admin.getIdLong()));

        channelService.postMessage(dedicated,
                "対象: " + displayName + "（ID: " + targetUserId + "）\n強制退会の理由を入力してください。");
        return true;
    }

    private Integer parseIndex(String rawInput, int size) {
        try {
            int n = Integer.parseInt(rawInput.strip());
            if (n < 1 || n > size) return null;
            return n - 1;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ═══════════════════════════════════════
    //  理由入力・確認
    // ═══════════════════════════════════════

    /** 手続きチャンネルへの投稿を処理する。対象セッションが無ければfalse。 */
    public boolean handleDedicatedChannelMessage(long channelId, String rawText, TextChannel channel) {
        PendingSession session = sessionsByChannelId.get(channelId);
        if (session == null) return false;

        String text = rawText == null ? "" : rawText.strip();

        switch (session.state) {
            case WAITING_REASON -> {
                if (text.isEmpty()) return true;
                session.reason = text;
                session.state = SessionState.WAITING_CONFIRM;
                channelService.postMessage(channel,
                        "対象: " + session.targetDisplayName + "\n理由: " + session.reason
                                + "\n\nこの内容で強制退会を実行してよろしいですか？「はい」または「いいえ」で回答してください。");
            }
            case WAITING_CONFIRM -> {
                if (text.equals("はい")) {
                    confirm(session, channel);
                } else if (text.equals("いいえ")) {
                    channelService.postMessage(channel, "取り消しました。このチャンネルは削除されます。");
                    sessionsByChannelId.remove(channelId);
                    channelService.scheduleChannelDelete(channel, 5);
                } else {
                    channelService.postMessage(channel, "「はい」または「いいえ」で回答してください。");
                }
            }
        }
        return true;
    }

    private void confirm(PendingSession session, TextChannel channel) {
        Instant now = Instant.now();
        ForcedWithdrawalRecord record = new ForcedWithdrawalRecord(
                UUID.randomUUID().toString(), session.targetUserId, session.guildId,
                session.adminUserId, session.targetDisplayName, session.reason);
        record.confirmedAt = now;
        record.executeAt = now.plus(EXECUTE_DELAY);
        record.state = ForcedWithdrawalState.CONFIRMED;
        repository.save(record);

        notifyTarget(session.guildId, session.targetUserId, session.reason);
        scheduleExecution(record);

        channelService.postMessage(channel, "確定しました。24時間後に処理を実行します。このチャンネルは削除されます。");
        sessionsByChannelId.remove(channel.getIdLong());
        channelService.scheduleChannelDelete(channel, 5);

        logger.info("強制退会を確定しました: targetUserId=" + session.targetUserId
                + " adminUserId=" + session.adminUserId);
    }

    /** 対象者の「お知らせ部屋」に理由付きで通知する。庭が無い（未完了）場合は通知をスキップする。 */
    private void notifyTarget(long guildId, long targetUserId, String reason) {
        if (jda == null) return;
        InitialSetupRecord setupRecord = initialSetupRepository.load(targetUserId, guildId);
        if (setupRecord.announceChannelId == null) {
            logger.info("お知らせ部屋が無いため強制退会の通知をスキップしました: userId=" + targetUserId);
            return;
        }
        TextChannel announceChannel = jda.getTextChannelById(setupRecord.announceChannelId);
        if (announceChannel == null) return;
        channelService.postMessage(announceChannel, NOTIFY_TEMPLATE.formatted(reason));
    }

    // ═══════════════════════════════════════
    //  実行（匿名化・削除・kick）
    // ═══════════════════════════════════════

    private void scheduleExecution(ForcedWithdrawalRecord record) {
        long delaySeconds = Math.max(0, Duration.between(Instant.now(), record.executeAt).toSeconds());
        scheduler.schedule(() -> executeForcedWithdrawal(record.id), delaySeconds, TimeUnit.SECONDS);
    }

    private void executeForcedWithdrawal(String recordId) {
        ForcedWithdrawalRecord record = repository.loadById(recordId);
        if (record == null || record.state != ForcedWithdrawalState.CONFIRMED) return;

        long userId = record.targetUserId;
        long guildId = record.guildId;

        // データの匿名化保存＋元データ削除（withdrawalフローの「匿名化して保存」と同じ処理）
        try {
            List<Evidence> evidences = evidenceRepository.loadAll(userId);
            anonymizedDataRepository.saveAnonymized(evidences);
        } catch (RuntimeException e) {
            logger.warning("強制退会に伴うEvidence匿名化に失敗しました (userId=" + userId + "): " + e.getMessage());
        }
        dataSubjectRightsService.forgetUser(userId);

        // 紬希の庭のクリーンアップ（存在すれば）
        Guild guild = jda != null ? jda.getGuildById(guildId) : null;
        InitialSetupRecord setupRecord = initialSetupRepository.load(userId, guildId);
        if (guild != null && (setupRecord.gardenCategoryId != null || setupRecord.chatChannelId != null)) {
            try {
                initialSetupChannelService.deleteGardenChannels(guild, setupRecord);
            } catch (RuntimeException e) {
                logger.warning("強制退会に伴う庭の削除に失敗しました (userId=" + userId + "): " + e.getMessage());
            }
        }
        setupRecord.gardenCategoryId = null;
        setupRecord.chatChannelId = null;
        setupRecord.logChannelId = null;
        setupRecord.announceChannelId = null;
        setupRecord.setupChannelId = null;
        setupRecord.state = InitialSetupState.NOT_STARTED;
        initialSetupRepository.save(setupRecord);

        // kick（在籍していれば）。既に自主退出済みの場合はスキップするが、上記のデータ処理は必ず行う。
        boolean kicked = false;
        if (guild != null) {
            var member = guild.getMemberById(userId);
            if (member != null) {
                try {
                    guild.kick(member).reason("強制退会（理由: " + record.reason + "）").complete();
                    kicked = true;
                } catch (RuntimeException e) {
                    logger.warning("強制退会のkickに失敗しました (userId=" + userId + "): " + e.getMessage());
                }
            }
        }

        record.state = ForcedWithdrawalState.EXECUTED;
        record.executedAt = Instant.now();
        repository.save(record);

        if (guild != null) {
            initialSetupChannelService.postAdminLog(guild,
                    "✅ 強制退会を実行しました: " + record.targetDisplayName + "（ID: " + userId + "）\n"
                            + "理由: " + record.reason + "\n"
                            + "キック: " + (kicked ? "実行済み" : "対象が既に不在のためスキップ")
                            + "\nデータ: 匿名化のうえ削除しました。");
        }

        logger.info("強制退会を実行しました: userId=" + userId + " kicked=" + kicked);
    }

    /** 起動時に呼ぶ。CONFIRMED状態のまま止まっていたレコードのスケジュールを再構築する。 */
    public void resumePendingSchedules() {
        for (ForcedWithdrawalRecord record : repository.loadByState(ForcedWithdrawalState.CONFIRMED)) {
            if (record.executeAt == null) continue;
            scheduleExecution(record);
        }
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
