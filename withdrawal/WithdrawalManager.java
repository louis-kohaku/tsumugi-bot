package tsumugi.withdrawal;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import tsumugi.admin.AdminNotificationService;
import tsumugi.memory.anonymized.AnonymizedDataRepository;
import tsumugi.memory.anonymized.SqliteAnonymizedDataRepository;
import tsumugi.memory.rights.DataSubjectRightsService;
import tsumugi.memory.store.EvidenceRepository;
import tsumugi.memory.store.sqlite.SqliteConnectionFactory;
import tsumugi.withdrawal.store.WithdrawalRepository;
import tsumugi.withdrawal.store.sqlite.SqliteWithdrawalRepository;

import java.util.logging.Logger;

/**
 * 退会機能全体の組み立て・エントリーポイントを担うファサード。
 * TsumugiApplication（起動処理）からはこのクラスだけを組み立てれば、
 * 内部の各コンポーネント（Repository/Service/ChannelService/AdminNotification）を
 * 個別に意識せずに済むようにする。InitialSetupManagerと対になる存在。
 */
public final class WithdrawalManager {

    private static final Logger logger = Logger.getLogger(WithdrawalManager.class.getName());

    private final WithdrawalService service;
    private final WithdrawalChannelService channelService;
    private final AdminNotificationService adminNotificationService;

    public WithdrawalManager(WithdrawalService service,
                              WithdrawalChannelService channelService,
                              AdminNotificationService adminNotificationService) {
        this.service = service;
        this.channelService = channelService;
        this.adminNotificationService = adminNotificationService;
    }

    /** 標準構成で組み立てるファクトリメソッド。記憶層の各リポジトリ・削除権サービスを共用する。 */
    public static WithdrawalManager createDefault(SqliteConnectionFactory connectionFactory,
                                                    EvidenceRepository evidenceRepository,
                                                    DataSubjectRightsService dataSubjectRightsService) {
        WithdrawalRepository repository = new SqliteWithdrawalRepository(connectionFactory);
        WithdrawalChannelService channelService = new WithdrawalChannelService();
        AdminNotificationService adminNotificationService = new AdminNotificationService();
        AnonymizedDataRepository anonymizedDataRepository = new SqliteAnonymizedDataRepository(connectionFactory);

        WithdrawalService service = new WithdrawalService(
                repository, channelService, dataSubjectRightsService,
                evidenceRepository, anonymizedDataRepository, adminNotificationService);

        return new WithdrawalManager(service, channelService, adminNotificationService);
    }

    /**
     * Bot起動完了直後に呼ぶ。以下をまとめて保証する。
     *  1. 参加している全ギルドに退会チャンネル・管理者通知チャンネルが存在すること
     *  2. 再起動をまたいでも、未確定の退会手続きの期限監視が再構築されること
     */
    public void bootstrapGuilds(JDA jda) {
        service.setJda(jda);
        for (Guild guild : jda.getGuilds()) {
            try {
                channelService.ensureEntryChannel(guild);
            } catch (RuntimeException e) {
                logger.warning("退会チャンネルの保証に失敗しました (guildId=" + guild.getIdLong() + "): " + e.getMessage());
            }
            try {
                adminNotificationService.ensureAdminNotificationChannel(guild);
            } catch (RuntimeException e) {
                logger.warning("管理者通知チャンネルの保証に失敗しました (guildId=" + guild.getIdLong() + "): " + e.getMessage());
            }
        }
        service.resumePendingSchedules();
    }

    /** 退会チャンネルへの投稿を受け取ったときにWithdrawalListenerから呼ばれる。 */
    public void handleEntryChannelMessage(Member member, String rawText) {
        if (member.getUser().isBot()) return;
        if (rawText == null || !rawText.contains("退会")) return;
        try {
            service.startWithdrawal(member);
        } catch (RuntimeException e) {
            logger.warning("退会手続き開始処理に失敗しました (userId=" + member.getIdLong() + "): " + e.getMessage());
        }
    }

    /** 退会専用チャンネルへの投稿（選択肢の回答）を受け取ったときにWithdrawalListenerから呼ばれる。 */
    public void handleDedicatedChannelMessage(Member member, String rawText, TextChannel channel) {
        if (member.getUser().isBot()) return;
        try {
            service.handleChoiceMessage(member, rawText, channel);
        } catch (RuntimeException e) {
            logger.warning("退会選択処理に失敗しました (userId=" + member.getIdLong() + "): " + e.getMessage());
        }
    }

    /** 管理者通知チャンネルへの投稿（承認コマンド）を受け取ったときにWithdrawalListenerから呼ばれる。 */
    public void handleAdminNotificationMessage(Member member, String rawText, Guild guild) {
        if (member.getUser().isBot()) return;
        Long targetUserId = adminNotificationService.parseApprovalCommand(rawText);
        if (targetUserId == null) return;
        try {
            boolean handled = service.handleAdminApproval(targetUserId, guild);
            if (!handled) {
                adminNotificationService.ensureAdminNotificationChannel(guild)
                        .sendMessage("⚠️ 対象のユーザー（ID: " + targetUserId + "）は現在監査待ち状態ではありません。").queue();
            }
        } catch (RuntimeException e) {
            logger.warning("管理者承認処理に失敗しました (userId=" + targetUserId + "): " + e.getMessage());
        }
    }

    public boolean isEntryChannel(String channelName) {
        return channelService.entryChannelName().equals(channelName);
    }

    public boolean isDedicatedChannel(String channelName) {
        return channelService.isDedicatedChannelName(channelName);
    }

    public boolean isAdminNotificationChannel(String channelName) {
        return adminNotificationService.isAdminNotificationChannel(channelName);
    }

    public void shutdown() {
        service.shutdown();
        channelService.shutdown();
    }
}
