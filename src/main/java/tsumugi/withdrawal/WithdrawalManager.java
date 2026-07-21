package tsumugi.withdrawal;

import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import tsumugi.initialsetup.InitialSetupChannelService;
import tsumugi.memory.rights.DataSubjectRightsService;
import tsumugi.memory.store.sqlite.SqliteConnectionFactory;
import tsumugi.withdrawal.model.WithdrawalRecord;
import tsumugi.withdrawal.store.WithdrawalRepository;
import tsumugi.withdrawal.store.sqlite.SqliteWithdrawalRepository;

import java.util.logging.Logger;

/**
 * 退会機能全体の組み立て・エントリーポイントを担うファサード。
 * TsumugiApplicationからはこのクラスだけを組み立てれば、
 * Repository/ChannelService/Serviceを個別に意識せずに済むようにする。
 */
public final class WithdrawalManager {

    private static final Logger logger = Logger.getLogger(WithdrawalManager.class.getName());

    private final WithdrawalService service;
    private final WithdrawalChannelService channelService;

    public WithdrawalManager(WithdrawalService service, WithdrawalChannelService channelService) {
        this.service = service;
        this.channelService = channelService;
    }

    /**
     * SQLiteベースの標準構成で組み立てるファクトリメソッド。
     * 管理者ログ通知は既存のInitialSetupChannelService（🌼｜管理者ログ）を使い回す。
     */
    public static WithdrawalManager createDefault(SqliteConnectionFactory connectionFactory,
                                                    DataSubjectRightsService dataSubjectRightsService,
                                                    InitialSetupChannelService adminNoticeChannelService) {
        WithdrawalRepository repository = new SqliteWithdrawalRepository(connectionFactory);
        return createDefault(repository, dataSubjectRightsService, adminNoticeChannelService);
    }

    /**
     * 既に組み立て済みのWithdrawalRepositoryを受け取って組み立てるファクトリメソッド。
     * MembershipManager等、他コンポーネントと同じWithdrawalRepositoryインスタンスを
     * 共有したい場合はこちらを使う（TsumugiApplication側でrepositoryを先に生成する）。
     */
    public static WithdrawalManager createDefault(WithdrawalRepository repository,
                                                    DataSubjectRightsService dataSubjectRightsService,
                                                    InitialSetupChannelService adminNoticeChannelService) {
        WithdrawalChannelService channelService = new WithdrawalChannelService();
        WithdrawalService service = new WithdrawalService(
                repository, channelService, dataSubjectRightsService, adminNoticeChannelService);
        return new WithdrawalManager(service, channelService);
    }

    /** 「退会」発言をどこかのチャンネルで検知したときにWithdrawalListenerから呼ばれる。 */
    public void handleWithdrawalTrigger(Member member) {
        if (member.getUser().isBot()) return;
        try {
            service.startWithdrawal(member);
        } catch (RuntimeException e) {
            logger.warning("退会手続き開始処理に失敗しました (userId=" + member.getIdLong() + "): " + e.getMessage());
        }
        // 「退会」発言など直近のやり取りを残さないよう、入室チャンネルと同様に5秒後リセットする
        channelService.scheduleWithdrawalRequestChannelRecreate(member.getGuild());
    }

    /** 退会チャンネル内での回答（1/2/3）を検知したときにWithdrawalListenerから呼ばれる。 */
    public void handleChoiceMessage(Member member, String rawText, TextChannel channel) {
        if (member.getUser().isBot()) return;
        try {
            if (!service.isWaitingForChoice(member.getIdLong(), member.getGuild().getIdLong())) return;
            service.confirmChoice(member, rawText, channel);
        } catch (RuntimeException e) {
            logger.warning("退会データ選択処理に失敗しました (userId=" + member.getIdLong() + "): " + e.getMessage());
        }
    }

    /** 管理者ログチャンネルでの手動削除確認コマンドを検知したときにWithdrawalListenerから呼ばれる。 */
    public boolean handleAdminDeleteConfirmed(long userId, long guildId) {
        try {
            return service.markDataManuallyDeleted(userId, guildId);
        } catch (RuntimeException e) {
            logger.warning("管理者による手動削除確認処理に失敗しました (userId=" + userId + "): " + e.getMessage());
            return false;
        }
    }

    /** 現在のチャンネル名が退会専用チャンネルかどうか判定するヘルパー（DiscordAdapter等から利用）。 */
    public boolean isWithdrawalChannelName(String channelName) {
        return channelService.isWithdrawalChannelName(channelName);
    }

    /** 現在のチャンネル名が常設の退会希望チャンネルかどうか判定するヘルパー。 */
    public boolean isWithdrawalRequestChannelName(String channelName) {
        return channelService.isWithdrawalRequestChannelName(channelName);
    }

    /** Bot起動完了直後に呼ぶ。参加している全ギルドに退会希望チャンネルが存在することを保証する。 */
    public void ensureWithdrawalRequestChannelsForAllGuilds(net.dv8tion.jda.api.JDA jda) {
        for (net.dv8tion.jda.api.entities.Guild guild : jda.getGuilds()) {
            try {
                channelService.ensureWithdrawalRequestChannel(guild);
            } catch (RuntimeException e) {
                logger.warning("退会希望チャンネルの保証に失敗しました (guildId=" + guild.getIdLong() + "): " + e.getMessage());
            }
        }
    }

    public WithdrawalRecord getRecord(long userId, long guildId) {
        return service.getRecord(userId, guildId);
    }

    public void shutdown() {
        service.shutdown();
    }
}
