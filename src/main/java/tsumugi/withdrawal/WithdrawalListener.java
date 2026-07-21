package tsumugi.withdrawal;

import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

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
 */
public final class WithdrawalListener extends ListenerAdapter {

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

        // 1. 管理者ログチャンネルでの手動削除確認コマンド
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

        // 2. 本人専用の退会チャンネル内での回答
        if (manager.isWithdrawalChannelName(channelName)) {
            manager.handleChoiceMessage(member, text, channel);
            return;
        }

        // 3. 常設の「退会希望」チャンネルでの「退会」発言のみをトリガーとする
        if (manager.isWithdrawalRequestChannelName(channelName) && text.contains(WITHDRAWAL_TRIGGER)) {
            manager.handleWithdrawalTrigger(member);
        }
    }
}
