package tsumugi.broadcast;

import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

/**
 * お知らせ配信フローに関するJDAイベントだけを受け取る薄いリスナー。
 * DiscordAdapter（通常会話）・他機能のListenerとは責務を分離する。
 *
 * 監視対象:
 *  - お知らせ配信チャンネル（🌼｜お知らせ配信）: 原文投稿・はい/いいえ・修正文の全てをここで拾う
 *    （状態に応じた振り分けはBroadcastService側で行う）
 */
public final class BroadcastListener extends ListenerAdapter {

    private final BroadcastManager manager;

    public BroadcastListener(BroadcastManager manager) {
        this.manager = manager;
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (event.getAuthor().isBot()) return;
        if (!event.isFromGuild()) return;
        if (event.getChannelType() != net.dv8tion.jda.api.entities.channel.ChannelType.TEXT) return;

        TextChannel channel = event.getChannel().asTextChannel();
        if (!manager.isBroadcastChannel(channel.getName())) return;

        Member member = event.getMember();
        if (member == null) return;

        String text = event.getMessage().getContentDisplay();
        manager.handleBroadcastChannelMessage(member, text, channel);
    }
}
