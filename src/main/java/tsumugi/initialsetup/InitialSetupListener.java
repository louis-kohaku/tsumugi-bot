package tsumugi.initialsetup;

import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRemoveEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

/**
 * 初期設定フローに関するJDAイベントだけを受け取る薄いリスナー。
 * DiscordAdapter（会話用リスナー）とは責務を分離し、
 * JDABuilderに両方を addEventListeners で登録する想定。
 *
 * 拾うメッセージ:
 *  - 入室チャンネル（🌼｜入室）: 名前入力として処理
 *  - 引継ぎ確認チャンネル（🌼｜確認-ユーザー名）: 「はい/いいえ」の回答として処理
 * それ以外のチャンネルのメッセージには一切反応しない（通常会話はDiscordAdapter側の担当）。
 */
public final class InitialSetupListener extends ListenerAdapter {

    private static final String ENTRY_CHANNEL_NAME = "🌼｜入室";
    private static final String REJOIN_CONFIRM_CHANNEL_PREFIX = "🌼｜確認-";

    private final InitialSetupManager manager;

    public InitialSetupListener(InitialSetupManager manager) {
        this.manager = manager;
    }

    @Override
    public void onGuildMemberJoin(GuildMemberJoinEvent event) {
        manager.handleMemberJoin(event.getMember());
    }

    @Override
    public void onGuildMemberRemove(GuildMemberRemoveEvent event) {
        String tag = event.getUser().getName();
        long userId = event.getUser().getIdLong();
        manager.handleMemberLeave(event.getGuild(), tag, userId);
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

        if (ENTRY_CHANNEL_NAME.equals(channelName)) {
            manager.handleEntryChannelMessage(member, text);
            return;
        }

        if (channelName != null && channelName.startsWith(REJOIN_CONFIRM_CHANNEL_PREFIX)) {
            manager.handleRejoinConfirmMessage(member, text);
        }
    }
}
