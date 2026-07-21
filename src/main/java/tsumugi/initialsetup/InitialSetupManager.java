package tsumugi.initialsetup;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import tsumugi.initialsetup.store.InitialSetupRepository;
import tsumugi.initialsetup.store.sqlite.SqliteInitialSetupRepository;
import tsumugi.membership.MembershipManager;
import tsumugi.membership.store.MembershipRepository;
import tsumugi.membership.store.sqlite.SqliteMembershipRepository;
import tsumugi.memory.store.sqlite.SqliteConnectionFactory;
import tsumugi.memory.store.sqlite.UserConnectionFactoryRegistry;
import tsumugi.withdrawal.store.WithdrawalRepository;

import java.util.logging.Logger;

/**
 * 初期設定機能全体の組み立て・エントリーポイントを担うファサード。
 * TsumugiApplication（起動処理）からはこのクラスだけを組み立てれば、
 * 内部の各コンポーネント（Repository/Service/ChannelService/Consent/Kick/Membership）を
 * 個別に意識せずに済むようにする。
 *
 * DiscordAdapter同様、JDAのイベント自体はInitialSetupListenerが受け取り、
 * このクラスへ処理を委譲する。入退室そのものの記録・振り分けはMembershipManagerが担う。
 */
public final class InitialSetupManager {

    private static final Logger logger = Logger.getLogger(InitialSetupManager.class.getName());

    private final InitialSetupService service;
    private final InitialSetupChannelService channelService;
    private final ConsentManager consentManager;
    private final KickManager kickManager;
    private final MembershipManager membershipManager;

    public InitialSetupManager(InitialSetupService service,
                                InitialSetupChannelService channelService,
                                ConsentManager consentManager,
                                KickManager kickManager,
                                MembershipManager membershipManager) {
        this.service = service;
        this.channelService = channelService;
        this.consentManager = consentManager;
        this.kickManager = kickManager;
        this.membershipManager = membershipManager;
    }

    /**
     * SQLiteベースの標準構成で組み立てるファクトリメソッド。
     *
     * @param connectionFactory  共有DB（initial_setup等）用の接続ファクトリ
     * @param withdrawalRepository  退会時の記名保持選択を参照して再入室時の
     *                               引継ぎ確認要否を判定するために使う
     *                               （TsumugiApplication側でWithdrawalManagerと同じインスタンスを共有すること）
     * @param userDbRegistry  記憶層のユーザーごとDBフォルダを「表示名+登録日時」で
     *                        割り当て・リネームするために使う
     *                        （TsumugiApplication側で記憶層Repository群と同じインスタンスを共有すること）
     */
    public static InitialSetupManager createDefault(SqliteConnectionFactory connectionFactory,
                                                      WithdrawalRepository withdrawalRepository,
                                                      UserConnectionFactoryRegistry userDbRegistry) {
        InitialSetupRepository repository = new SqliteInitialSetupRepository(connectionFactory);
        InitialSetupChannelService channelService = new InitialSetupChannelService();
        ConsentManager consentManager = new ConsentManager();
        KickManager kickManager = new KickManager();
        InitialSetupService service = new InitialSetupService(
                repository, channelService, consentManager, kickManager, userDbRegistry);

        MembershipRepository membershipRepository = new SqliteMembershipRepository(connectionFactory);
        MembershipManager membershipManager = new MembershipManager(membershipRepository, withdrawalRepository, service);

        return new InitialSetupManager(service, channelService, consentManager, kickManager, membershipManager);
    }

    /**
     * Bot起動完了直後に呼ぶ。以下をまとめて保証する。
     *  1. 参加している全ギルドに入室チャンネルが存在すること
     *     （初回導入時や、Botが落ちていた間に手動で消された場合のフォールバック）
     *  2. 管理者専用ログチャンネルが存在すること
     *  3. 既にサーバーにいるメンバー（Bot導入前から在籍している人・管理者含む）も、
     *     入室チャンネルに名前を投稿すれば紬希の庭を作れる状態（WAITING_NAME）になっていること
     *
     * ※ guild.getMembers() はメンバーキャッシュに依存するため、大規模サーバーでは
     *   全メンバーが揃っていない可能性がある。TODO: 必要ならguild.loadMembers()で明示的に取得する。
     */
    public void bootstrapGuilds(JDA jda) {
        for (Guild guild : jda.getGuilds()) {
            try {
                channelService.ensureEntryChannel(guild);
            } catch (RuntimeException e) {
                logger.warning("入室チャンネルの保証に失敗しました (guildId=" + guild.getIdLong() + "): " + e.getMessage());
            }
            try {
                channelService.ensureAdminLogChannel(guild);
            } catch (RuntimeException e) {
                logger.warning("管理者ログチャンネルの保証に失敗しました (guildId=" + guild.getIdLong() + "): " + e.getMessage());
            }
            for (Member member : guild.getMembers()) {
                if (member.getUser().isBot()) continue;
                try {
                    service.startSetup(member); // 既にWAITING_NAME以降の場合は内部で何もしない
                } catch (RuntimeException e) {
                    logger.warning("既存メンバーの初期設定準備に失敗しました (userId=" + member.getIdLong() + "): " + e.getMessage());
                }
            }
        }
    }

    /**
     * GuildMemberJoinEvent受信時にInitialSetupListenerから呼ばれる。
     * 入室イベントの記録・引継ぎ確認要否の判定・通常フローへの分岐は全てMembershipManagerに委譲する。
     */
    public void handleMemberJoin(Member member) {
        if (member.getUser().isBot()) return;
        try {
            membershipManager.recordEnter(member);
        } catch (RuntimeException e) {
            logger.warning("入室処理に失敗しました (userId=" + member.getIdLong() + "): " + e.getMessage());
        }
        channelService.postAdminLog(member.getGuild(),
                "🟢 入室: " + member.getUser().getName() + "（ID: " + member.getIdLong() + "）");
    }

    /**
     * GuildMemberRemoveEvent受信時にInitialSetupListenerから呼ばれる。
     * 退室イベントの記録・庭チャンネルの即時削除はMembershipManagerに委譲する。
     */
    public void handleMemberLeave(Guild guild, String userTag, long userId) {
        try {
            membershipManager.recordLeave(guild, userId);
        } catch (RuntimeException e) {
            logger.warning("退室処理に失敗しました (userId=" + userId + "): " + e.getMessage());
        }
        try {
            channelService.postAdminLog(guild, "🔴 退室: " + userTag + "（ID: " + userId + "）");
        } catch (RuntimeException e) {
            logger.warning("退室ログの送信に失敗しました (userId=" + userId + "): " + e.getMessage());
        }
    }

    /**
     * 入室チャンネルへのメッセージ投稿を受け取ったときにInitialSetupListenerから呼ばれる。
     * WAITING_NAME状態のユーザーでなければ何もしない。
     */
    public void handleEntryChannelMessage(Member member, String rawText) {
        if (member.getUser().isBot()) return;
        try {
            if (!service.isWaitingForName(member.getIdLong(), member.getGuild().getIdLong())) return;
            service.handleNameEntered(member, rawText);
        } catch (RuntimeException e) {
            logger.warning("名前入力処理に失敗しました (userId=" + member.getIdLong() + "): " + e.getMessage());
        }
    }

    /**
     * 引継ぎ確認チャンネルへのメッセージ投稿を受け取ったときにInitialSetupListenerから呼ばれる。
     * WAITING_REJOIN_CONFIRM状態のユーザーでなければ何もしない。
     */
    public void handleRejoinConfirmMessage(Member member, String rawText) {
        if (member.getUser().isBot()) return;
        try {
            if (!service.isWaitingForRejoinConfirm(member.getIdLong(), member.getGuild().getIdLong())) return;
            service.handleRejoinConfirmAnswer(member, rawText);
        } catch (RuntimeException e) {
            logger.warning("引継ぎ確認処理に失敗しました (userId=" + member.getIdLong() + "): " + e.getMessage());
        }
    }

    public InitialSetupService getService() {
        return service;
    }

    public InitialSetupChannelService getChannelService() {
        return channelService;
    }

    public ConsentManager getConsentManager() {
        return consentManager;
    }

    public void shutdown() {
        kickManager.shutdown();
        channelService.shutdown();
    }
}
