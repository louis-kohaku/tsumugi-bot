package tsumugi.initialsetup;

import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRemoveEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

import java.util.logging.Logger;

/**
 * 初期設定フローに関するJDAイベントだけを受け取る薄いリスナー。
 * DiscordAdapter（会話用リスナー）とは責務を分離し、
 * JDABuilderに両方を addEventListeners で登録する想定。
 *
 * 拾うメッセージ:
 *  - 入室チャンネル（🌼｜入室）: 名前入力として処理
 *  - 利用規約同意チャンネル（🌼｜利用規約確認-ユーザー名）: 「同意する/同意しない」の回答として処理
 *  - 引継ぎ確認チャンネル（🌼｜確認-ユーザー名）: 「はい/いいえ」の回答として処理
 * それ以外のチャンネルのメッセージには一切反応しない（通常会話はDiscordAdapter側の担当）。
 *
 * 【変更点】入室チャンネルは@everyoneに見える常設チャンネルであり、
 * 従来は「5秒後にチャンネルごと削除→再作成」（InitialSetupChannelService.scheduleEntryChannelRecreate）
 * によってのみ投稿内容（名前などの個人的な入力）を消していたが、それまでの5秒間は
 * 他の利用者からも見えてしまう。これを避けるため、入室チャンネルへの投稿は
 * 処理後にその場で即座に削除するようにした（チャンネル自体の再作成は既存の仕組みのまま維持し、
 * 二重の安全策とする）。
 * 同意チャンネル・引継ぎ確認チャンネルは本人・Bot・管理者ロールのみ閲覧可能な
 * 一時チャンネルのため、削除対象には含めていない。
 */
public final class InitialSetupListener extends ListenerAdapter {

    private static final Logger logger = Logger.getLogger(InitialSetupListener.class.getName());

    private static final String ENTRY_CHANNEL_NAME = "🌼｜入室";
    private static final String CONSENT_CHANNEL_PREFIX = "🌼｜利用規約確認-";
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
            // 入室チャンネルは@everyoneに見える常設チャンネルのため、
            // 5秒後のチャンネル再作成（scheduleEntryChannelRecreate）を待たず、
            // 投稿された個人的な入力（名前など）はその場で即座に削除する。
            deleteQuietly(event.getMessage());
            return;
        }

        if (channelName != null && channelName.startsWith(CONSENT_CHANNEL_PREFIX)) {
            manager.handleConsentChannelMessage(member, text);
            return;
        }

        if (channelName != null && channelName.startsWith(REJOIN_CONFIRM_CHANNEL_PREFIX)) {
            manager.handleRejoinConfirmMessage(member, text);
        }
    }

    /**
     * @everyoneに見える常設チャンネル（入室チャンネル）上の個人的な投稿を即時削除するためのヘルパー。
     * 権限不足・既に削除済み等で失敗しても会話フロー自体は継続させたいため、例外は投げずログのみ出す。
     * 削除にはBot自身にMESSAGE_MANAGE権限が必要（InitialSetupChannelService側の権限設定も要確認）。
     */
    private void deleteQuietly(Message message) {
        message.delete().queue(
                success -> {},
                failure -> logger.fine("入室チャンネルのメッセージ削除に失敗しました（無視して継続）: " + failure.getMessage())
        );
    }
}
