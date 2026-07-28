package tsumugi.broadcast;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import tsumugi.broadcast.store.BroadcastRepository;
import tsumugi.broadcast.store.sqlite.SqliteBroadcastRepository;
import tsumugi.initialsetup.store.InitialSetupRepository;
import tsumugi.llm.LlmClient;
import tsumugi.memory.store.sqlite.SqliteConnectionFactory;

import java.util.logging.Logger;

/**
 * お知らせ配信機能全体の組み立て・エントリーポイントを担うファサード。
 * TsumugiApplication（起動処理）からはこのクラスだけを組み立てれば、
 * 内部の各コンポーネント（Repository/Service/ChannelService/Reviewer）を
 * 個別に意識せずに済むようにする（WithdrawalManager/DiaryManagerと同じ方針）。
 */
public final class BroadcastManager {

    private static final Logger logger = Logger.getLogger(BroadcastManager.class.getName());

    private final BroadcastService service;
    private final BroadcastChannelService channelService;

    public BroadcastManager(BroadcastService service, BroadcastChannelService channelService) {
        this.service = service;
        this.channelService = channelService;
    }

    /**
     * @param broadcastLlmClient お知らせ文チェックに使うLlmClient。
     *                           呼び出し側（TsumugiApplication）で LlmLane.BROADCAST に紐づいた
     *                           LaneLlmClient を渡すこと。
     */
    public static BroadcastManager createDefault(SqliteConnectionFactory connectionFactory,
                                                   LlmClient broadcastLlmClient,
                                                   InitialSetupRepository initialSetupRepository) {
        BroadcastRepository repository = new SqliteBroadcastRepository(connectionFactory);
        BroadcastChannelService channelService = new BroadcastChannelService();
        BroadcastReviewer reviewer = new BroadcastReviewer(broadcastLlmClient);
        BroadcastService service = new BroadcastService(reviewer, channelService, repository, initialSetupRepository);
        return new BroadcastManager(service, channelService);
    }

    /** Bot起動完了直後に呼ぶ。参加している全ギルドにお知らせ配信チャンネルが存在することを保証する。 */
    public void bootstrapGuilds(JDA jda) {
        service.setJda(jda);
        for (Guild guild : jda.getGuilds()) {
            try {
                channelService.ensureBroadcastChannel(guild);
            } catch (RuntimeException e) {
                logger.warning("お知らせ配信チャンネルの保証に失敗しました (guildId=" + guild.getIdLong() + "): " + e.getMessage());
            }
        }
    }

    /** お知らせ配信チャンネルへの投稿を受け取ったときにBroadcastListenerから呼ばれる。 */
    public void handleBroadcastChannelMessage(Member member, String rawText, TextChannel channel) {
        if (member.getUser().isBot()) return;
        try {
            service.handleMessage(member.getGuild(), rawText, channel);
        } catch (RuntimeException e) {
            logger.warning("お知らせ配信処理に失敗しました (guildId=" + member.getGuild().getIdLong() + "): " + e.getMessage());
        }
    }

    public boolean isBroadcastChannel(String channelName) {
        return channelService.isBroadcastChannel(channelName);
    }
}
