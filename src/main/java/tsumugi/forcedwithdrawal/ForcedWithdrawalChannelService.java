package tsumugi.forcedwithdrawal;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * 強制退会関連チャンネルの命名規則・権限設定を担うサービス。
 * WithdrawalChannelService/InitialSetupChannelServiceと同じ命名規則（🌼｜チャンネル名）に揃える。
 *
 * 扱うチャンネル構成:
 *  - 強制退会チャンネル（常設）: 管理者ロール・Botのみ閲覧可能。ここに対象者名を投稿すると検索が始まる。
 *  - 強制退会手続きチャンネル（本人ごと・一時）: 管理者ロール・Botのみ閲覧可能。対象本人には見せない
 *    （通知は別途「お知らせ部屋」経由で行うため）。
 */
public final class ForcedWithdrawalChannelService {

    private static final Logger logger = Logger.getLogger(ForcedWithdrawalChannelService.class.getName());

    private static final String CHANNEL_PREFIX = "🌼｜";
    private static final String ENTRY_CHANNEL_NAME = "強制退会";
    private static final String DEDICATED_BASE_NAME = "強制退会手続き";

    public static final String ENTRY_STANDING_MESSAGE =
            "強制退会させたい対象者の表示名（部分一致可）を入力してください。";

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "forced-withdrawal-channel-scheduler");
                t.setDaemon(true);
                return t;
            });

    private String buildName(String category, String suffix) {
        String base = CHANNEL_PREFIX + category;
        if (suffix == null || suffix.isBlank()) return sanitize(base);
        return sanitize(base + "-" + suffix);
    }

    private String sanitize(String name) {
        String trimmed = name.strip();
        return trimmed.length() > 100 ? trimmed.substring(0, 100) : trimmed;
    }

    public String entryChannelName() {
        return buildName(ENTRY_CHANNEL_NAME, null);
    }

    public boolean isDedicatedChannelName(String channelName) {
        return channelName != null && channelName.startsWith(buildName(DEDICATED_BASE_NAME, null));
    }

    /** 管理者ロール・Botのみ閲覧可能な、強制退会チャンネル（常設）が無ければ作成する。冪等。 */
    public TextChannel ensureEntryChannel(Guild guild) {
        String name = entryChannelName();
        TextChannel existing = findChannelByName(guild, name);
        if (existing != null) return existing;
        return createEntryChannel(guild, name);
    }

    private TextChannel createEntryChannel(Guild guild, String name) {
        var self = guild.getSelfMember();
        TextChannel channel = guild.createTextChannel(name)
                .addPermissionOverride(guild.getPublicRole(), null, List.of(Permission.VIEW_CHANNEL))
                .addPermissionOverride(self,
                        List.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_HISTORY,
                                Permission.MANAGE_CHANNEL, Permission.MESSAGE_MANAGE),
                        null)
                .complete();

        guild.getRoles().stream()
                .filter(role -> role.hasPermission(Permission.ADMINISTRATOR))
                .forEach(adminRole -> channel.getManager()
                        .putRolePermissionOverride(adminRole.getIdLong(),
                                List.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_HISTORY),
                                null)
                        .queue());

        channel.sendMessage(ENTRY_STANDING_MESSAGE).queue();
        logger.info("強制退会チャンネルを作成しました: guildId=" + guild.getIdLong());
        return channel;
    }

    private TextChannel findChannelByName(Guild guild, String name) {
        return guild.getTextChannelsByName(name, true).stream().findFirst().orElse(null);
    }

    /** 対象者ごとの一時手続きチャンネルを作成する。対象本人には見せない（管理者・Botのみ）。 */
    public TextChannel createDedicatedChannel(Guild guild, String targetDisplayName) {
        String name = buildName(DEDICATED_BASE_NAME, targetDisplayName);
        var self = guild.getSelfMember();

        TextChannel channel = guild.createTextChannel(name)
                .addPermissionOverride(guild.getPublicRole(), null, List.of(Permission.VIEW_CHANNEL))
                .addPermissionOverride(self,
                        List.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_HISTORY,
                                Permission.MANAGE_CHANNEL),
                        null)
                .complete();

        guild.getRoles().stream()
                .filter(role -> role.hasPermission(Permission.ADMINISTRATOR))
                .forEach(adminRole -> channel.getManager()
                        .putRolePermissionOverride(adminRole.getIdLong(),
                                List.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_HISTORY),
                                null)
                        .queue());

        logger.info("強制退会手続きチャンネルを作成しました: name=" + name + " guildId=" + guild.getIdLong());
        return channel;
    }

    public void postMessage(TextChannel channel, String message) {
        if (channel == null) return;
        channel.sendMessage(message).queue(
                success -> {},
                failure -> logger.warning("メッセージ送信に失敗しました (channelId=" + channel.getIdLong() + "): " + failure.getMessage())
        );
    }

    public void scheduleChannelDelete(TextChannel channel, long delaySeconds) {
        if (channel == null) return;
        scheduler.schedule(() -> {
            try {
                channel.delete().reason("強制退会手続き完了のためチャンネルを削除").complete();
            } catch (RuntimeException e) {
                logger.warning("強制退会手続きチャンネルの削除に失敗しました (channelId=" + channel.getIdLong() + "): " + e.getMessage());
            }
        }, delaySeconds, TimeUnit.SECONDS);
    }

    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
