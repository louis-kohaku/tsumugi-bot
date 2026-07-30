package tsumugi.forcedwithdrawal;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import tsumugi.forcedwithdrawal.store.ForcedWithdrawalRepository;
import tsumugi.forcedwithdrawal.store.sqlite.SqliteForcedWithdrawalRepository;
import tsumugi.initialsetup.InitialSetupChannelService;
import tsumugi.initialsetup.store.InitialSetupRepository;
import tsumugi.memory.anonymized.AnonymizedDataRepository;
import tsumugi.memory.rights.DataSubjectRightsService;
import tsumugi.memory.store.EvidenceRepository;
import tsumugi.memory.store.sqlite.SqliteConnectionFactory;

import java.util.logging.Logger;

/**
 * 強制退会機能全体の組み立て・エントリーポイントを担うファサード。
 * WithdrawalManager/InitialSetupManagerと同じ方針。
 */
public final class ForcedWithdrawalManager {

    private static final Logger logger = Logger.getLogger(ForcedWithdrawalManager.class.getName());

    private final ForcedWithdrawalService service;
    private final ForcedWithdrawalChannelService channelService;

    public ForcedWithdrawalManager(ForcedWithdrawalService service, ForcedWithdrawalChannelService channelService) {
        this.service = service;
        this.channelService = channelService;
    }

    /** 記憶層・初期設定層の各Repositoryを共用して組み立てる標準ファクトリメソッド。 */
    public static ForcedWithdrawalManager createDefault(SqliteConnectionFactory sharedConnectionFactory,
                                                          InitialSetupRepository initialSetupRepository,
                                                          InitialSetupChannelService initialSetupChannelService,
                                                          EvidenceRepository evidenceRepository,
                                                          AnonymizedDataRepository anonymizedDataRepository,
                                                          DataSubjectRightsService dataSubjectRightsService) {
        ForcedWithdrawalRepository repository = new SqliteForcedWithdrawalRepository(sharedConnectionFactory);
        ForcedWithdrawalChannelService channelService = new ForcedWithdrawalChannelService();
        ForcedWithdrawalService service = new ForcedWithdrawalService(
                repository, channelService, initialSetupRepository, initialSetupChannelService,
                evidenceRepository, anonymizedDataRepository, dataSubjectRightsService);
        return new ForcedWithdrawalManager(service, channelService);
    }

    /** Bot起動完了直後に呼ぶ。強制退会チャンネルの保証と、未完了スケジュールの再構築を行う。 */
    public void bootstrapGuilds(JDA jda) {
        service.setJda(jda);
        for (Guild guild : jda.getGuilds()) {
            try {
                channelService.ensureEntryChannel(guild);
            } catch (RuntimeException e) {
                logger.warning("強制退会チャンネルの保証に失敗しました (guildId=" + guild.getIdLong() + "): " + e.getMessage());
            }
        }
        service.resumePendingSchedules();
    }

    public void handleEntryChannelMessage(Member admin, Guild guild, String rawText, TextChannel channel) {
        if (admin.getUser().isBot()) return;
        if (channel == null) return;
        try {
            boolean handledAsSelection = service.selectCandidate(admin, guild, rawText, channel);
            if (!handledAsSelection) {
                service.searchCandidates(admin, guild, rawText, channel);
            }
        } catch (RuntimeException e) {
            logger.warning("強制退会の対象検索に失敗しました (adminUserId=" + admin.getIdLong() + "): " + e.getMessage());
        }
    }

    public void handleDedicatedChannelMessage(String rawText, TextChannel channel) {
        try {
            service.handleDedicatedChannelMessage(channel.getIdLong(), rawText, channel);
        } catch (RuntimeException e) {
            logger.warning("強制退会の手続き処理に失敗しました (channelId=" + channel.getIdLong() + "): " + e.getMessage());
        }
    }

    public boolean isEntryChannel(String channelName) {
        return channelService.entryChannelName().equals(channelName);
    }

    public boolean isDedicatedChannel(String channelName) {
        return channelService.isDedicatedChannelName(channelName);
    }

    public void shutdown() {
        service.shutdown();
        channelService.shutdown();
    }
}
