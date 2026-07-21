package tsumugi.initialsetup;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import tsumugi.initialsetup.InitialSetupChannelService.GardenChannels;
import tsumugi.initialsetup.model.InitialSetupRecord;
import tsumugi.initialsetup.store.InitialSetupRepository;
import tsumugi.memory.store.sqlite.UserConnectionFactoryRegistry;

import java.util.logging.Logger;

/**
 * 初期設定フローの状態遷移を管理する中核サービス。
 *
 * 現在のフロー（簡略版。同意フロー等は将来復活させる前提で骨組みだけ残す）:
 *
 *   参加 → startSetup() → WAITING_NAME
 *     （入室チャンネルの固定メッセージを見て、本人が名前を投稿するのを待つ）
 *   → handleNameEntered() で名前を受信
 *     → ユーザー用DBフォルダを「表示名+登録日時」で割り当て（初回 or リネーム）
 *     → 「🌼｜紬希の庭-名前」カテゴリ＋雑談部屋/ログ部屋/お知らせ部屋を作成
 *     → COMPLETED
 *     → 5秒後に入室チャンネルを削除→再作成（次の参加者のためにリセット）
 *
 * 退室時:
 *   tearDownGarden() で庭チャンネルを削除し、レコードをNOT_STARTEDへ戻す
 *   （displayNameだけは再入室時の引継ぎ確認メッセージに使うため保持する）
 *
 * 退会（記名保持）→再入室時:
 *   startRejoinConfirm() で確認チャンネルを作成しWAITING_REJOIN_CONFIRMへ
 *   → handleRejoinConfirmAnswer() で「はい/いいえ」を受け、
 *     庭の再構築（COMPLETED）か通常フロー（WAITING_NAME）かに分岐
 *
 * TODO: 利用規約・プライバシーポリシー・データ利用同意（WAITING_CONSENT等）は
 *       名前入力の後、庭チャンネル作成の前に挟む形で復活させる想定。
 */
public final class InitialSetupService {

    private static final Logger logger = Logger.getLogger(InitialSetupService.class.getName());

    private static final String YES_KEYWORD = "はい";
    private static final String NO_KEYWORD = "いいえ";

    private final InitialSetupRepository repository;
    private final InitialSetupChannelService channelService;
    private final ConsentManager consentManager;
    private final KickManager kickManager;
    private final UserConnectionFactoryRegistry userDbRegistry;

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
        repository.save(record);

        buildGardenAndComplete(guild, member, record, displayName);

        channelService.scheduleEntryChannelRecreate(guild);

        logger.info("初期設定が完了しました: userId=" + member.getIdLong() + " displayName=" + displayName);
    }

    /**
     * 庭チャンネルを作成し、recordに反映してCOMPLETEDへ遷移する共通処理。
     * ユーザー用DBフォルダの割り当て/リネームもここで行う
     * （通常の名前入力・引継ぎ確認の「はい」回答の両方から呼ばれるため）。
     */
    private void buildGardenAndComplete(Guild guild, Member member, InitialSetupRecord record, String displayName) {
        userDbRegistry.renameUser(member.getIdLong(), displayName);

        GardenChannels garden = channelService.createGardenChannels(guild, member, displayName);
        record.gardenCategoryId = garden.categoryId;
        record.chatChannelId = garden.chatChannelId;
        record.logChannelId = garden.logChannelId;
        record.announceChannelId = garden.announceChannelId;

        // TODO: ここで本来はWAITING_CONSENT等へ進めるが、現状は同意フロー未実装のためCOMPLETED扱いとする。
        transition(record, InitialSetupState.COMPLETED);
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
            buildGardenAndComplete(guild, member, record, record.displayName);
            logger.info("記憶の引継ぎを承諾しました: userId=" + member.getIdLong());
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

    public boolean isWaitingForRejoinConfirm(long userId, long guildId) {
        return repository.load(userId, guildId).state == InitialSetupState.WAITING_REJOIN_CONFIRM;
    }
}
