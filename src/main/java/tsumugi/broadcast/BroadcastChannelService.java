package tsumugi.broadcast;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;

import java.util.List;
import java.util.logging.Logger;

/**
 * Kohaku専用の「お知らせ配信」チャンネルの命名規則・権限設定を担うサービス。
 * AdminNotificationServiceと同じ方針（サーバーに1つだけ常設、管理者ロール＋Botのみ閲覧可）。
 */
public final class BroadcastChannelService {

    private static final Logger logger = Logger.getLogger(BroadcastChannelService.class.getName());

    public static final String BROADCAST_CHANNEL_NAME = "🌼｜お知らせ配信";

    public static final String STANDING_MESSAGE = """
        ここは全ユーザーへのお知らせを配信するための専用チャンネルです。
        配信したい内容をそのまま送信してください。紬希が文章をチェックし、
        配信してよいか確認します。
        """;

    public boolean isBroadcastChannel(String channelName) {
        return BROADCAST_CHANNEL_NAME.equals(channelName);
    }

    /** お知らせ配信チャンネルが無ければ作成する。何度呼んでも安全（冪等）。 */
    public TextChannel ensureBroadcastChannel(Guild guild) {
        TextChannel existing = guild.getTextChannelsByName(BROADCAST_CHANNEL_NAME, true)
                .stream().findFirst().orElse(null);
        if (existing != null) return existing;
        return createBroadcastChannel(guild);
    }

    private TextChannel createBroadcastChannel(Guild guild) {
        var self = guild.getSelfMember();
        TextChannel channel = guild.createTextChannel(BROADCAST_CHANNEL_NAME)
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

        channel.sendMessage(STANDING_MESSAGE).queue();
        logger.info("お知らせ配信チャンネルを作成しました: guildId=" + guild.getIdLong());
        return channel;
    }

    public void postMessage(TextChannel channel, String message) {
        channel.sendMessage(message).queue(
                success -> {},
                failure -> logger.warning("お知らせ配信チャンネルへの送信に失敗しました: " + failure.getMessage())
        );
    }
}
