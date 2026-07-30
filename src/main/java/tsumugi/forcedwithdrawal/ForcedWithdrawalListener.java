package tsumugi.forcedwithdrawal;

import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

/**
 * 強制退会フローに関するJDAイベントだけを受け取る薄いリスナー。
 * DiscordAdapter（通常会話）・WithdrawalListener（本人発の退会）とは責務を分離する。
 *
 * 監視対象:
 *  - 強制退会チャンネル（🌼｜強制退会・常設・管理者専用）: 対象者名検索・番号選択
 *  - 強制退会手続きチャンネル（🌼｜強制退会手続き-対象者名・一時・管理者専用）: 理由入力・確認
 */
public final class ForcedWithdrawalListener extends ListenerAdapter {

    private final ForcedWithdrawalManager manager;

    public ForcedWithdrawalListener(ForcedWithdrawalManager manager) {
        this.manager = manager;
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (event.getAuthor().isBot()) return;
        if (!event.isFromGuild()) return;

        MessageChannel channel = event.getChannel();
        String channelName = channel.getName();
        Member member = event.getMember();
        if (member == null) return;

        String text = event.getMessage().getContentDisplay();

        if (manager.isEntryChannel(channelName)) {
            TextChannel textChannel = channel instanceof TextChannel tc ? tc : null;
            manager.handleEntryChannelMessage(member, event.getGuild(), text, textChannel);
        } else if (manager.isDedicatedChannel(channelName) && channel instanceof TextChannel textChannel) {
            manager.handleDedicatedChannelMessage(text, textChannel);
        }
    }
}
