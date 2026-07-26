package tsumugi.withdrawal;

import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 退会フローに関するJDAイベントだけを受け取る薄いリスナー。
 * DiscordAdapter（通常会話）・InitialSetupListener（初期設定）とは責務を分離する。
 *
 * 扱うイベント:
 *  1. 常設の「🌼｜退会希望」チャンネルでの「退会」発言 → 本人専用退会チャンネル作成のトリガー
 *  2. 本人専用の退会チャンネル内での回答（1/2/3） → データの扱いの確定
 *  3. 管理者ログチャンネルでの「削除済み &lt;userId&gt;」 → 手動削除の確認（3日後自動削除のキャンセル）
 *
 * 【変更点】常設の「退会希望」チャンネルは@everyoneに見えるため、
 * 従来は「5秒後にチャンネルごと削除→再作成」（WithdrawalChannelService.scheduleEntryChannelRecreate）
 * によってのみ「退会」という投稿内容を消していたが、それまでの5秒間は他の利用者からも見えてしまう。
 * これを避けるため、退会希望チャンネルへの投稿はトリガーとして処理した直後にその場で即座に削除する
 * ようにした（チャンネル自体の再作成は既存の仕組みのまま維持し、二重の安全策とする）。
 * 本人専用の退会チャンネル・管理者ログチャンネルは本人・Bot・管理者ロールのみ閲覧可能なため、
 * 削除対象には含めていない。
 */
public final class WithdrawalListener extends ListenerAdapter {

    private static final Logger logger = Logger.getLogger(WithdrawalListener.class.getName());

    private static final String ADMIN_LOG_CHANNEL_NAME = "🌼｜管理者ログ";
    private static final String WITHDRAWAL_TRIGGER = "退会";

    private static final Pattern ADMIN_DELETE_CONFIRM_PATTERN =
            Pattern.compile("^削除済み\\s+(\\d+)$");

    private final WithdrawalManager manager;

    public WithdrawalListener(WithdrawalManager manager) {
        this.manager = manager;
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (event.getAuthor().isBot()) return;
        if (!event.isFromGuild()) return;
        if (event.getChannelType() != net.dv8tion.jda.api.entities.channel.ChannelType.TEXT) return;

        TextChannel channel = event.getChannel().asTextChannel();
        String channelName = channel.getName();
        String text = event.getMessage().getContentDisplay();
        if (text == null || text.isBlank()) return;

        Member member = event.getMember();

        // 1. 管理者ログチャンネルでの手動削除確認コマンド（管理者のみ閲覧可能なので削除不要）
        if (ADMIN_LOG_CHANNEL_NAME.equals(channelName)) {
            Matcher matcher = ADMIN_DELETE_CONFIRM_PATTERN.matcher(text.strip());
            if (matcher.matches()) {
                long userId = Long.parseLong(matcher.group(1));
                boolean handled = manager.handleAdminDeleteConfirmed(userId, event.getGuild().getIdLong());
                channel.sendMessage(handled
                        ? "✅ 手動削除を確認しました（userId=" + userId + "）。自動削除の予約はキャンセルしました。"
                        : "⚠️ 対象の退会レコードが見つかりませんでした（userId=" + userId + "）。").queue();
            }
            return;
        }

        if (member == null) return;

        // 2. 本人専用の退会チャンネル内での回答（本人・管理者のみ閲覧可能なので削除不要）
        if (manager.isWithdrawalChannelName(channelName)) {
            manager.handleChoiceMessage(member, text, channel);
            return;
        }

        // 3. 常設の「退会希望」チャンネル（@everyoneに見える）での「退会」発言のみをトリガーとする
        if (manager.isWithdrawalRequestChannelName(channelName) && text.contains(WITHDRAWAL_TRIGGER)) {
            manager.handleWithdrawalTrigger(member);
            // handleWithdrawalTrigger内部で5秒後のチャンネル再作成はスケジュールされるが、
            // それまでの間「退会」という投稿自体が他の利用者にも見えてしまうため、即座に削除する。
            deleteQuietly(event.getMessage());
        }
    }

    /**
     * @everyoneに見える常設チャンネル（退会希望チャンネル）上の投稿を即時削除するためのヘルパー。
     * 権限不足・既に削除済み等で失敗しても手続き自体は継続させたいため、例外は投げずログのみ出す。
     * 削除にはBot自身にMESSAGE_MANAGE権限が必要（WithdrawalChannelService側の権限設定も要確認）。
     */
    private void deleteQuietly(Message message) {
        message.delete().queue(
                success -> {},
                failure -> logger.fine("退会希望チャンネルのメッセージ削除に失敗しました（無視して継続）: " + failure.getMessage())
        );
    }
}
