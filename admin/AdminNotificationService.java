package tsumugi.admin;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;

import java.util.List;
import java.util.logging.Logger;

/**
 * 管理者依頼を専用チャンネルへ通知するための管理者ツール。
 *
 * 「🌼｜管理者通知」チャンネルを各ギルドに1つ常設し、管理者（ADMINISTRATOR権限ロール）と
 * Botのみが閲覧できる状態にする。退会の監査依頼をはじめ、今後増えうる管理者対応依頼は
 * すべてこのサービス経由で通知する想定（InitialSetupChannelServiceの入退室ログとは別系統）。
 *
 * 対応コマンドは今のところチャット文字列ベース（例: 「承認 123456789012345678」）とし、
 * WithdrawalListenerがこのチャンネルでの投稿を監視して処理する。
 */
public final class AdminNotificationService {

    private static final Logger logger = Logger.getLogger(AdminNotificationService.class.getName());

    public static final String ADMIN_NOTIFICATION_CHANNEL_NAME = "🌼｜管理者通知";

    public boolean isAdminNotificationChannel(String channelName) {
        return ADMIN_NOTIFICATION_CHANNEL_NAME.equals(channelName);
    }

    /** 管理者通知チャンネルが無ければ作成する。何度呼んでも安全（冪等）。 */
    public TextChannel ensureAdminNotificationChannel(Guild guild) {
        TextChannel existing = guild.getTextChannelsByName(ADMIN_NOTIFICATION_CHANNEL_NAME, true)
                .stream().findFirst().orElse(null);
        if (existing != null) return existing;
        return createAdminNotificationChannel(guild);
    }

    private TextChannel createAdminNotificationChannel(Guild guild) {
        var self = guild.getSelfMember();
        TextChannel channel = guild.createTextChannel(ADMIN_NOTIFICATION_CHANNEL_NAME)
                .addPermissionOverride(guild.getPublicRole(), null, List.of(Permission.VIEW_CHANNEL))
                .addPermissionOverride(self,
                        List.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_HISTORY, Permission.MANAGE_CHANNEL),
                        null)
                .complete();

        guild.getRoles().stream()
                .filter(role -> role.hasPermission(Permission.ADMINISTRATOR))
                .forEach(adminRole -> channel.getManager()
                        .putRolePermissionOverride(adminRole.getIdLong(),
                                List.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_HISTORY),
                                null)
                        .queue());

        logger.info("管理者通知チャンネルを作成しました: guildId=" + guild.getIdLong());
        return channel;
    }

    /** 退会の監査依頼を通知する。承認コマンドの書式もあわせて案内する。 */
    public void postWithdrawalAuditRequest(Guild guild, long userId, String displayName) {
        TextChannel channel = ensureAdminNotificationChannel(guild);
        String name = displayName != null && !displayName.isBlank() ? displayName : "（名前未設定）";
        String message = """
            📋 管理者依頼: 退会データの監査対応
            対象ユーザー: %s（ID: %d）
            内容: 「管理者による監査の上で削除」が選択されました。会話履歴・記憶データを確認のうえ、削除して問題なければ以下のコマンドでこのチャンネルに返信してください。

            承認 %d

            ※ このコマンドで即座にデータが削除されます（取り消せません）。
            ※ 3日以内に対応がない場合は自動的に削除されます。
            """.formatted(name, userId, userId);
        channel.sendMessage(message).queue(
                success -> {},
                failure -> logger.warning("管理者依頼の通知に失敗しました (userId=" + userId + "): " + failure.getMessage())
        );
    }

    public void postWithdrawalResolved(Guild guild, long userId, String resultSummary) {
        TextChannel channel = ensureAdminNotificationChannel(guild);
        channel.sendMessage("✅ 退会対応が完了しました（ID: " + userId + "）: " + resultSummary).queue(
                success -> {},
                failure -> logger.warning("完了通知の送信に失敗しました (userId=" + userId + "): " + failure.getMessage())
        );
    }

    /** 「承認 &lt;userId&gt;」形式のコマンドをパースする。該当しなければnull。 */
    public Long parseApprovalCommand(String text) {
        if (text == null) return null;
        String trimmed = text.strip();
        if (!trimmed.startsWith("承認")) return null;
        String rest = trimmed.substring("承認".length()).strip();
        try {
            return Long.parseLong(rest);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
