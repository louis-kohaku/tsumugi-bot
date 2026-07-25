package tsumugi.initialsetup;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import tsumugi.initialsetup.InitialSetupChannelService.GardenChannels;
import tsumugi.initialsetup.model.InitialSetupRecord;
import tsumugi.initialsetup.store.InitialSetupRepository;
import tsumugi.memory.store.sqlite.UserConnectionFactoryRegistry;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * 初期設定フローの状態遷移を管理する中核サービス。
 *
 * 現在のフロー:
 *
 *   参加 → startSetup() → WAITING_NAME
 *     （入室チャンネルの固定メッセージを見て、本人が名前を投稿するのを待つ）
 *   → handleNameEntered() で名前を受信
 *     → 利用規約同意チャンネル（🌼｜利用規約確認-名前）を作成 → WAITING_CONSENT
 *       → handleConsentAnswer() で「同意する/同意しない」を受信（1分間の自動タイムアウトあり）
 *         → 同意する: 続けて庭作成へ進む（buildGardenAndComplete） → COMPLETED
 *         → 同意しない（またはタイムアウト）: displayNameをクリアし NOT_STARTED へ戻す
 *           （＝入室チャンネルへの名前入力からやり直し）
 *     → 5秒後に入室チャンネルを削除→再作成（次の参加者のためにリセット）
 *
 * 退室時:
 *   tearDownGarden() で庭チャンネルを削除し、レコードをNOT_STARTEDへ戻す
 *   （displayNameだけは再入室時の引継ぎ確認メッセージに使うため保持する）
 *
 * 退会（記名保持）→再入室時:
 *   startRejoinConfirm() で確認チャンネルを作成しWAITING_REJOIN_CONFIRMへ
 *   → handleRejoinConfirmAnswer() で「はい/いいえ」を受け、
 *     「はい」の場合は通常の名前入力後と同じ利用規約同意フロー（WAITING_CONSENT）へ進み、
 *     「いいえ」の場合は通常フロー（WAITING_NAME）へ切り替える
 */
public final class InitialSetupService {
    private volatile java.util.function.Consumer<Member> onDisplayNameConfirmed;

    /**
     * 表示名確定（名前入力 or 引継ぎ確認完了）のタイミングで呼ばれるコールバックを登録する。
     * DiaryManager等、初期設定完了を起点に個人チャンネルを用意したい機能から利用する想定。
     * InitialSetupServiceは呼び出し先の実体（Diary等）を一切知らずに済むよう、
     * Consumer<Member>という薄いインタフェースのみに依存する。
     */
    public void setOnDisplayNameConfirmed(java.util.function.Consumer<Member> callback) {
        this.onDisplayNameConfirmed = callback;
    }

    private static final Logger logger = Logger.getLogger(InitialSetupService.class.getName());

    private static final String YES_KEYWORD = "はい";
    private static final String NO_KEYWORD = "いいえ";

    private static final String AGREE_KEYWORD = "同意する";
    private static final String DISAGREE_KEYWORD = "同意しない";
    private static final Duration CONSENT_TIMEOUT = Duration.ofMinutes(1);

    private final InitialSetupRepository repository;
    private final InitialSetupChannelService channelService;
    private final ConsentManager consentManager;
    private final KickManager kickManager;
    private final UserConnectionFactoryRegistry userDbRegistry;

    /** 利用規約同意の1分タイムアウト監視専用スケジューラ。 */
    private final ScheduledExecutorService consentScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "initialsetup-consent-scheduler");
                t.setDaemon(true);
                return t;
            });

    /** userId+guildIdをキーにした、保留中の同意タイムアウト（明示回答が来たらキャンセルする）。 */
    private final Map<String, ScheduledFuture<?>> pendingConsentTimeouts = new ConcurrentHashMap<>();

    public InitialSetupService(InitialSetupRepository repository,
                                InitialSetupChannelService channelService,
                                ConsentManager consentManager,
                                KickManager kickManager,
                                UserConnectionFactoryRegistry userDbRegistry) {
        this.repository = repository;
        this.channelService = channelService;
        this.consentManager = consentManager;
        this.kickManager = kickManager;
        this.userDbRegistry = userDbRegistry;
    }

    /** サーバー参加時に呼ばれるエントリーポイント。入室チャンネルの存在保証と、状態をWAITING_NAMEにするだけ。 */
    public void startSetup(Member member) {
        Guild guild = member.getGuild();
        channelService.ensureEntryChannel(guild);

        InitialSetupRecord record = repository.load(member.getIdLong(), guild.getIdLong());
        if (record.state != InitialSetupState.NOT_STARTED) {
            logger.info("既に初期設定が開始/完了済みのため、状態変更をスキップします: userId=" + member.getIdLong()
                    + " state=" + record.state);
            return;
        }
        transition(record, InitialSetupState.WAITING_NAME);
    }

    /**
     * 入室チャンネルに投稿されたメッセージを名前入力として処理する。
     * WAITING_NAME以外の状態のユーザーからの投稿は無視する（呼び出し側でも判定するが、二重チェック）。
     * 名前を受け付けたら、続けて利用規約同意フローを開始する。
     */
    public void handleNameEntered(Member member, String rawName) {
        Guild guild = member.getGuild();
        InitialSetupRecord record = repository.load(member.getIdLong(), guild.getIdLong());
        if (record.state != InitialSetupState.WAITING_NAME) {
            return;
        }

        String displayName = rawName == null ? "" : rawName.strip();
        if (displayName.isEmpty()) {
            logger.fine("空の名前入力を無視しました: userId=" + member.getIdLong());
            return;
        }

        record.displayName = displayName;
        startConsentFlow(guild, member, record);

        channelService.scheduleEntryChannelRecreate(guild);

        logger.info("名前入力を受け付け、利用規約同意フローを開始しました: userId=" + member.getIdLong() + " displayName=" + displayName);
    }

    // ═══════════════════════════════════════
    //  利用規約同意フロー
    // ═══════════════════════════════════════

    /**
     * 利用規約同意チャンネルを作成し、WAITING_CONSENTへ遷移して1分のタイムアウトを仕込む。
     * 通常の名前入力後・再入室の引継ぎ「はい」回答後の両方から呼ばれる。
     * 呼び出し前提として、record.displayNameは設定済みであること。
     */
    private void startConsentFlow(Guild guild, Member member, InitialSetupRecord record) {
        TextChannel consentChannel = channelService.createConsentChannel(guild, member, record.displayName);
        record.setupChannelId = consentChannel.getIdLong();
        transition(record, InitialSetupState.WAITING_CONSENT);

        scheduleConsentTimeout(member);
    }

    private void scheduleConsentTimeout(Member member) {
        long userId = member.getIdLong();
        long guildId = member.getGuild().getIdLong();
        String key = consentKey(userId, guildId);

        ScheduledFuture<?> future = consentScheduler.schedule(() -> {
            try {
                InitialSetupRecord record = repository.load(userId, guildId);
                if (record.state != InitialSetupState.WAITING_CONSENT) return; // 既に回答済み
                logger.info("利用規約同意が1分以内に得られなかったため、同意なしとして扱います: userId=" + userId);
                handleConsentResult(member.getGuild(), member, record, false);
            } catch (RuntimeException e) {
                logger.warning("同意タイムアウト処理に失敗しました (userId=" + userId + "): " + e.getMessage());
            } finally {
                pendingConsentTimeouts.remove(key);
            }
        }, CONSENT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);

        pendingConsentTimeouts.put(key, future);
    }

    private void cancelConsentTimeout(long userId, long guildId) {
        ScheduledFuture<?> future = pendingConsentTimeouts.remove(consentKey(userId, guildId));
        if (future != null) future.cancel(false);
    }

    private String consentKey(long userId, long guildId) {
        return userId + ":" + guildId;
    }

    /**
     * 利用規約同意チャンネルでの回答を処理する。
     * WAITING_CONSENT以外の状態のユーザーからの投稿は無視する。
     *
     * @return 有効な回答（同意する/同意しない）として処理できた場合true
     */
    public boolean handleConsentAnswer(Member member, String rawText) {
        Guild guild = member.getGuild();
        InitialSetupRecord record = repository.load(member.getIdLong(), guild.getIdLong());
        if (record.state != InitialSetupState.WAITING_CONSENT) {
            return false;
        }

        String answer = rawText == null ? "" : rawText.strip();
        boolean agreed;
        if (answer.contains(DISAGREE_KEYWORD)) {
            agreed = false;
        } else if (answer.contains(AGREE_KEYWORD)) {
            agreed = true;
        } else {
            return false; // 「同意する」「同意しない」以外は無視（呼び出し側で再入力を促す想定）
        }

        cancelConsentTimeout(member.getIdLong(), guild.getIdLong());
        handleConsentResult(guild, member, record, agreed);
        return true;
    }

    /**
     * 同意結果（明示回答・タイムアウトどちらも）の共通処理。
     * 同意した場合は既存の庭作成フローへ、同意しなかった場合は名前入力からやり直しにする。
     */
    private void handleConsentResult(Guild guild, Member member, InitialSetupRecord record, boolean agreed) {
        consentManager.recordConsent(record.userId, ConsentManager.ConsentType.TERMS_OF_SERVICE, agreed);

        Long consentChannelId = record.setupChannelId;
        record.setupChannelId = null;

        if (agreed) {
            buildGardenAndComplete(guild, member, record, record.displayName);
            logger.info("利用規約への同意を確認しました: userId=" + member.getIdLong());
        } else {
            record.displayName = null; // 入室チャンネルでの名前入力からやり直してもらう
            transition(record, InitialSetupState.NOT_STARTED);
            channelService.ensureEntryChannel(guild);
            logger.info("利用規約に同意されなかったため、初期設定を最初からやり直します: userId=" + member.getIdLong());
        }

        if (consentChannelId != null) {
            TextChannel channel = guild.getTextChannelById(consentChannelId);
            if (channel != null) channelService.deleteChannel(channel);
        }
    }

    /**
     * 庭チャンネルを作成し、recordに反映してCOMPLETEDへ遷移する共通処理。
     * ユーザー用DBフォルダの割り当て/リネームもここで行う
     * （通常の同意完了・引継ぎ確認完了の両方から呼ばれるため）。
     */
    private void buildGardenAndComplete(Guild guild, Member member, InitialSetupRecord record, String displayName) {
        userDbRegistry.renameUser(member.getIdLong(), displayName);

        GardenChannels garden = channelService.createGardenChannels(guild, member, displayName);
        record.gardenCategoryId = garden.categoryId;
        record.chatChannelId = garden.chatChannelId;
        record.logChannelId = garden.logChannelId;
        record.announceChannelId = garden.announceChannelId;

        transition(record, InitialSetupState.COMPLETED);

        // 名前確定（新規入室・引継ぎ確認完了の両方）を機能横断で通知する
        if (onDisplayNameConfirmed != null) {
            try {
                onDisplayNameConfirmed.accept(member);
            } catch (RuntimeException e) {
                logger.warning("表示名確定コールバックの実行に失敗しました (userId=" + member.getIdLong() + "): " + e.getMessage());
            }
        }
    }

    // ═══════════════════════════════════════
    //  退室時のクリーンアップ
    // ═══════════════════════════════════════

    /**
     * 退室時に呼ぶ。庭チャンネル（存在すれば）を削除し、レコードをNOT_STARTEDへ戻す。
     * displayNameは再入室時の引継ぎ確認で使うため保持したまま残す。
     * ユーザー用DBフォルダはそのまま（削除しない。記憶データ自体は退会/削除権の対象）。
     */
    public void tearDownGarden(Guild guild, long userId) {
        InitialSetupRecord record = repository.load(userId, guild.getIdLong());

        if (record.gardenCategoryId != null || record.chatChannelId != null
                || record.logChannelId != null || record.announceChannelId != null) {
            channelService.deleteGardenChannels(guild, record);
        }

        record.gardenCategoryId = null;
        record.chatChannelId = null;
        record.logChannelId = null;
        record.announceChannelId = null;
        record.setupChannelId = null;
        transition(record, InitialSetupState.NOT_STARTED);

        logger.info("退室に伴い紬希の庭をクリーンアップしました: userId=" + userId);
    }

    // ═══════════════════════════════════════
    //  退会（記名保持）→再入室時の引継ぎ確認
    // ═══════════════════════════════════════

    /**
     * 退会時に「記名で保持」を選んでいたユーザーが再入室した際に呼ぶ。
     * 確認チャンネルを作成し、WAITING_REJOIN_CONFIRMへ遷移する。
     */
    public void startRejoinConfirm(Member member, String previousDisplayName) {
        Guild guild = member.getGuild();
        InitialSetupRecord record = repository.load(member.getIdLong(), guild.getIdLong());

        record.displayName = previousDisplayName;
        var confirmChannel = channelService.createRejoinConfirmChannel(guild, member, previousDisplayName);
        record.setupChannelId = confirmChannel.getIdLong();
        transition(record, InitialSetupState.WAITING_REJOIN_CONFIRM);

        logger.info("引継ぎ確認を開始しました: userId=" + member.getIdLong() + " previousDisplayName=" + previousDisplayName);
    }

    /**
     * 確認チャンネルでの「はい/いいえ」回答を処理する。
     * WAITING_REJOIN_CONFIRM以外の状態のユーザーからの投稿は無視する。
     *
     * 「はい」の場合は、記憶を引き継ぐ前提として通常の名前入力後と同じ
     * 利用規約同意フロー（WAITING_CONSENT）へ進める。
     * 「いいえ」の場合は、通常の初期設定フロー（WAITING_NAME）へ切り替える。
     *
     * @return 有効な回答として処理できた場合true（呼び出し側でのメッセージ送信要否判断に使う）
     */
    public boolean handleRejoinConfirmAnswer(Member member, String rawText) {
        Guild guild = member.getGuild();
        InitialSetupRecord record = repository.load(member.getIdLong(), guild.getIdLong());
        if (record.state != InitialSetupState.WAITING_REJOIN_CONFIRM) {
            return false;
        }

        String answer = rawText == null ? "" : rawText.strip();
        Long confirmChannelId = record.setupChannelId;

        if (answer.contains(YES_KEYWORD)) {
            record.setupChannelId = null;
            repository.save(record);
            if (confirmChannelId != null) {
                var oldChannel = guild.getTextChannelById(confirmChannelId);
                if (oldChannel != null) channelService.deleteChannel(oldChannel);
            }
            // 引継ぎ承諾 → 記憶を引き継ぐ前提で、通常フローと同じく利用規約同意を挟む
            startConsentFlow(guild, member, record);
            logger.info("記憶の引継ぎを承諾し、利用規約同意フローへ進みました: userId=" + member.getIdLong());
            return true;
        } else if (answer.contains(NO_KEYWORD)) {
            transition(record, InitialSetupState.WAITING_NAME);
            channelService.ensureEntryChannel(guild);
            logger.info("記憶の引継ぎを辞退したため、通常の初期設定フローへ切り替えました: userId=" + member.getIdLong());
        } else {
            return false; // 「はい」「いいえ」以外は無視（呼び出し側で再入力を促す）
        }

        record.setupChannelId = null;
        repository.save(record);

        if (confirmChannelId != null) {
            var channel = guild.getTextChannelById(confirmChannelId);
            if (channel != null) channelService.deleteChannel(channel);
        }
        return true;
    }

    private void transition(InitialSetupRecord record, InitialSetupState next) {
        InitialSetupState previous = record.state;
        record.state = next;
        repository.save(record);
        logger.info("初期設定状態を遷移しました: userId=" + record.userId
                + " " + previous + " -> " + next);
    }

    public InitialSetupRecord getRecord(long userId, long guildId) {
        return repository.load(userId, guildId);
    }

    public boolean isWaitingForName(long userId, long guildId) {
        return repository.load(userId, guildId).state == InitialSetupState.WAITING_NAME;
    }

    public boolean isWaitingForConsent(long userId, long guildId) {
        return repository.load(userId, guildId).state == InitialSetupState.WAITING_CONSENT;
    }

    public boolean isWaitingForRejoinConfirm(long userId, long guildId) {
        return repository.load(userId, guildId).state == InitialSetupState.WAITING_REJOIN_CONFIRM;
    }

    public void shutdown() {
        kickManager.shutdown();
        channelService.shutdown();
        consentScheduler.shutdown();
        try {
            if (!consentScheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                consentScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            consentScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
