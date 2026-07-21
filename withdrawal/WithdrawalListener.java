package tsumugi.withdrawal;

import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

/**
 * 退会フローに関するJDAイベントだけを受け取る薄いリスナー。
 * DiscordAdapter（通常会話）・InitialSetupListener（入室）とは責務を分離する。
 * JDABuilderにこのリスナーも addEventListeners で登録する想定。
 *
 * 監視対象:
 *  - 退会チャンネル（🌼｜退会）: 「退会」投稿をトリガーに手続き開始
 *  - 退会専用チャンネル（🌼｜退会手続き-ユーザー名）: 1/2/3の選択を受付
 *  - 管理者通知チャンネル（🌼｜管理者通知）: 「承認 &lt;userId&gt;」コマンドを受付
 */
public final class WithdrawalListener extends ListenerAdapter {

    private final WithdrawalManager manager;

    public WithdrawalListener(WithdrawalManager manager) {
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
            manager.handleEntryChannelMessage(member, text);
        } else if (manager.isDedicatedChannel(channelName) && channel instanceof TextChannel textChannel) {
            manager.handleDedicatedChannelMessage(member, text, textChannel);
        } else if (manager.isAdminNotificationChannel(channelName)) {
            manager.handleAdminNotificationMessage(member, text, event.getGuild());
        }
    }
}
