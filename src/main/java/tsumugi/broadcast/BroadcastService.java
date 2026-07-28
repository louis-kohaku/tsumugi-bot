package tsumugi.broadcast;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import tsumugi.broadcast.model.BroadcastHistoryEntry;
import tsumugi.broadcast.store.BroadcastRepository;
import tsumugi.initialsetup.InitialSetupState;
import tsumugi.initialsetup.model.InitialSetupRecord;
import tsumugi.initialsetup.store.InitialSetupRepository;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * お知らせ配信フローの状態遷移・配信実行を管理する中核サービス。
 * WithdrawalService/DiarySessionManagerに相当する存在。
 *
 * フロー:
 *   配信専用チャンネルへの原文投稿 → startSession() → WAITING_REVIEW（LLM校正）
 *     → WAITING_CONFIRM（校正結果提示、はい/いいえ待ち）
 *         「はい」: broadcast() で全ユーザーのお知らせ部屋へ一斉配信 → セッション終了
 *         「いいえ」: WAITING_REVISIONへ（修正文の投稿待ち）
 *           → 修正文投稿 → 再度WAITING_REVIEW（往復修正）
 *
 * セッションはguildIdをキーにインメモリ管理のみ（DiarySessionと同じ方針）。
 * 配信先は、初期設定完了済みユーザー（InitialSetupState.COMPLETED）の announceChannelId。
 */
public final class BroadcastService {

    private static final Logger logger = Logger.getLogger(BroadcastService.class.getName());

    private static final String YES_KEYWORD = "はい";
    private static final String NO_KEYWORD = "いいえ";

    private final BroadcastReviewer reviewer;
    private final BroadcastChannelService channelService;
    private final BroadcastRepository repository;
    private final InitialSetupRepository initialSetupRepository;

    private final Map<Long, BroadcastSession> sessions = new ConcurrentHashMap<>();

    /** 全ユーザーのお知らせ部屋を探すためJDAを後から注入する（起動順序の都合上）。 */
    private volatile JDA jda;

    public BroadcastService(BroadcastReviewer reviewer,
                             BroadcastChannelService channelService,
                             BroadcastRepository repository,
                             InitialSetupRepository initialSetupRepository) {
        this.reviewer = reviewer;
        this.channelService = channelService;
        this.repository = repository;
        this.initialSetupRepository = initialSetupRepository;
    }

    public void setJda(JDA jda) {
        this.jda = jda;
    }

    public boolean hasActiveSession(long guildId) {
        BroadcastSession session = sessions.get(guildId);
        return session != null && session.state != BroadcastState.NOT_IN_SESSION;
    }

    /**
     * 配信専用チャンネルへの投稿を受けて呼ばれる。
     * セッション未開始・WAITING_REVISION中の投稿は「原文（または修正文）」として扱い、
     * WAITING_CONFIRM中の投稿は「はい/いいえ」として扱う（呼び出し元で状態を見て振り分けてもよいが、
     * ここに集約することでBroadcastManager側をシンプルに保つ）。
     */
    public void handleMessage(Guild guild, String rawText, TextChannel channel) {
        long guildId = guild.getIdLong();
        BroadcastSession session = sessions.computeIfAbsent(guildId, BroadcastSession::new);
        String text = rawText == null ? "" : rawText.strip();
        if (text.isBlank()) return;

        switch (session.state) {
            case NOT_IN_SESSION, WAITING_REVISION -> handleContentSubmission(session, text, channel);
            case WAITING_CONFIRM -> handleConfirmAnswer(guild, session, text, channel);
            case WAITING_REVIEW -> channelService.postMessage(channel, "只今チェック中です。少しお待ちください。");
        }
    }

    /** 原文（新規 or 修正後）の投稿を受け、LLM校正へ回す。 */
    private void handleContentSubmission(BroadcastSession session, String content, TextChannel channel) {
        if (session.state == BroadcastState.NOT_IN_SESSION) {
            session.originalContent = content;
        }
        session.state = BroadcastState.WAITING_REVIEW;

        String reviewed = reviewer.review(content);
        session.currentDraft = reviewed;
        session.state = BroadcastState.WAITING_CONFIRM;

        String message = reviewed.equals(content.strip())
                ? "文章のチェックをしましたが、特に問題ありませんでした。この内容で配信してよいですか？「はい」または「いいえ」でお答えください。\n\n" + reviewed
                : "文章を一部直しました。この内容で配信してよいですか？「はい」または「いいえ」でお答えください。\n\n" + reviewed;
        channelService.postMessage(channel, message);
    }

    /** WAITING_CONFIRM中の「はい/いいえ」回答を処理する。 */
    private void handleConfirmAnswer(Guild guild, BroadcastSession session, String answer, TextChannel channel) {
        if (answer.contains(YES_KEYWORD)) {
            executeBroadcast(guild, session, channel);
        } else if (answer.contains(NO_KEYWORD)) {
            session.state = BroadcastState.WAITING_REVISION;
            channelService.postMessage(channel, "承知しました。修正後の内容を送信してください。");
        } else {
            channelService.postMessage(channel, "「はい」または「いいえ」でお答えください。");
        }
    }

    /** 配信を実行し、履歴を保存してセッションを終了する。 */
    private void executeBroadcast(Guild guild, BroadcastSession session, TextChannel channel) {
        String content = session.currentDraft;
        int successCount = 0;
        int failureCount = 0;

        if (jda == null) {
            logger.warning("JDAが未設定のため配信を実行できません: guildId=" + guild.getIdLong());
            channelService.postMessage(channel, "配信に失敗しました（内部エラー）。時間をおいて再度お試しください。");
            return;
        }

        for (InitialSetupRecord record : initialSetupRepository.loadByState(InitialSetupState.COMPLETED)) {
            if (record.guildId != guild.getIdLong()) continue;
            if (record.announceChannelId == null) {
                failureCount++;
                logger.warning("お知らせ部屋が未登録のためスキップしました: userId=" + record.userId);
                continue;
            }
            TextChannel target = jda.getTextChannelById(record.announceChannelId);
            if (target == null) {
                failureCount++;
                logger.warning("お知らせ部屋が見つからないためスキップしました: userId=" + record.userId
                        + " channelId=" + record.announceChannelId);
                continue;
            }
            try {
                target.sendMessage(content).queue(
                        success -> {},
                        failure -> logger.warning("お知らせ配信に失敗しました: userId=" + record.userId
                                + " : " + failure.getMessage())
                );
                successCount++;
            } catch (RuntimeException e) {
                failureCount++;
                logger.warning("お知らせ配信中に例外が発生しました: userId=" + record.userId + ": " + e.getMessage());
            }
        }

        BroadcastHistoryEntry entry = new BroadcastHistoryEntry(
                guild.getIdLong(), session.originalContent, content, successCount, failureCount);
        repository.save(entry);

        channelService.postMessage(channel,
                "配信しました（成功: " + successCount + "件 / 失敗: " + failureCount + "件）。");

        sessions.remove(guild.getIdLong());
        logger.info("お知らせ配信が完了しました: guildId=" + guild.getIdLong()
                + " success=" + successCount + " failure=" + failureCount);
    }
}
