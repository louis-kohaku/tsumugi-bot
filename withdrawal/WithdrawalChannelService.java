package tsumugi.withdrawal;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * 退会関連チャンネルの命名規則・権限設定を一元管理するサービス。
 * InitialSetupChannelService（入室）と対になる存在。
 *
 * 扱うチャンネル構成:
 *  - 退会チャンネル: サーバーに1つだけ常設。@everyoneに見える固定案内文を表示し、
 *    ここに「退会」と投稿されたことをトリガーに退会専用チャンネルを作成する。
 *    使用後は一度削除→再作成することで、常にまっさらな状態を保つ（入室チャンネルと同じ方式）。
 *  - 退会専用チャンネル: 本人専用。3つの選択肢（匿名で保存/監査の上で削除/次回利用のため保存）を
 *    案内する固定メッセージを表示する。
 */
public final class WithdrawalChannelService {

    private static final Logger logger = Logger.getLogger(WithdrawalChannelService.class.getName());

    private static final String CHANNEL_PREFIX = "🌼｜";
    private static final String WITHDRAWAL_ENTRY_CHANNEL_NAME = "退会";
    private static final String WITHDRAWAL_DEDICATED_BASE_NAME = "退会手続き";

    public static final String WITHDRAWAL_ENTRY_STANDING_MESSAGE =
            "退会をご希望の場合は、こちらに「退会」と入力してください。";

    public static final String WITHDRAWAL_CHOICE_PROMPT = """
        退会手続き用のチャンネルです。データの取り扱いについて、以下から1つ選んで数字（1・2・3）を入力してください。

        1. 消す（匿名で保存） … あなたを特定できる情報は削除し、感情・習慣などの傾向データのみ研究目的で匿名保存します。
        2. 消す（管理者による監査の上で削除） … 管理者が内容を確認したうえで削除します。3日以内に対応が無い場合は自動的に削除されます。
        3. 次の利用のため保存 … データを削除せずそのまま保持します（いつでも再開できます）。

        ※ このチャンネルは選択後、自動的に案内が更新されます。3日以内にいずれも選ばれない場合は、安全のため自動的に削除（通常削除）されます。
        """;

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "withdrawal-channel-scheduler");
                t.setDaemon(true);
                return t;
            });

    private String buildChannelName(String category, String suffix) {
        String base = CHANNEL_PREFIX + category;
        if (suffix == null || suffix.isBlank()) return sanitize(base);
        return sanitize(base + "-" + suffix);
    }

    private String sanitize(String name) {
        String trimmed = name.strip();
        return trimmed.length() > 100 ? trimmed.substring(0, 100) : trimmed;
    }

    public String entryChannelName() {
        return buildChannelName(WITHDRAWAL_ENTRY_CHANNEL_NAME, null);
    }

    /** チャンネル名が退会専用チャンネル（本人用）かどうかを判定する。 */
    public boolean isDedicatedChannelName(String channelName) {
        if (channelName == null) return false;
        return channelName.startsWith(buildChannelName(WITHDRAWAL_DEDICATED_BASE_NAME, null));
    }

    // ═══════════════════════════════════════
    //  退会チャンネル（入口）
    // ═══════════════════════════════════════

    /** 退会チャンネルが無ければ作成する。何度呼んでも安全（冪等）。 */
    public TextChannel ensureEntryChannel(Guild guild) {
        String name = entryChannelName();
        TextChannel existing = findChannelByName(guild, name);
        if (existing != null) return existing;
        return createEntryChannel(guild, name);
    }

    private TextChannel createEntryChannel(Guild guild, String name) {
        TextChannel channel = guild.createTextChannel(name)
                .addPermissionOverride(guild.getPublicRole(),
                        List.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_HISTORY),
                        null)
                .addPermissionOverride(guild.getSelfMember(),
                        List.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MANAGE_CHANNEL),
                        null)
                .complete();
        channel.sendMessage(WITHDRAWAL_ENTRY_STANDING_MESSAGE).queue();
        logger.info("退会チャンネルを作成しました: guildId=" + guild.getIdLong());
        return channel;
    }

    /** 5秒後に退会チャンネルを一度削除し、まっさらな状態で作り直す（入室チャンネルと同じ方式）。 */
    public void scheduleEntryChannelRecreate(Guild guild) {
        scheduler.schedule(() -> {
            try {
                String name = entryChannelName();
                TextChannel existing = findChannelByName(guild, name);
                if (existing != null) {
                    existing.delete().reason("退会チャンネルのリセット").complete();
                }
                createEntryChannel(guild, name);
            } catch (RuntimeException e) {
                logger.warning("退会チャンネルの再作成に失敗しました (guildId=" + guild.getIdLong() + "): " + e.getMessage());
            }
        }, 5, TimeUnit.SECONDS);
    }

    private TextChannel findChannelByName(Guild guild, String name) {
        return guild.getTextChannelsByName(name, true).stream().findFirst().orElse(null);
    }

    // ═══════════════════════════════════════
    //  退会専用チャンネル（本人専用）
    // ═══════════════════════════════════════

    /**
     * 「🌼｜退会手続き-ユーザー名」チャンネルを作成する。
     * 対象ユーザー・Bot・管理者ロールのみ閲覧可能（@everyoneには非表示）。
     */
    public TextChannel createDedicatedChannel(Guild guild, Member targetMember, String displayName) {
        String name = buildChannelName(WITHDRAWAL_DEDICATED_BASE_NAME, displayName);
        var self = guild.getSelfMember();

        TextChannel channel = guild.createTextChannel(name)
                .addPermissionOverride(guild.getPublicRole(), null, List.of(Permission.VIEW_CHANNEL))
                .addPermissionOverride(targetMember,
                        List.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_HISTORY),
                        null)
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

        channel.sendMessage(WITHDRAWAL_CHOICE_PROMPT).queue();
        logger.info("退会専用チャンネルを作成しました: userId=" + targetMember.getIdLong() + " guildId=" + guild.getIdLong());
        return channel;
    }

    public void postCompletionMessage(TextChannel channel, String message) {
        channel.sendMessage(message).queue(
                success -> {},
                failure -> logger.warning("退会完了メッセージの送信に失敗しました: " + failure.getMessage())
        );
    }

    /** 手続き完了後、5秒後にこのチャンネル自体を削除する。 */
    public void scheduleDedicatedChannelDelete(TextChannel channel) {
        scheduler.schedule(() -> {
            try {
                channel.delete().reason("退会手続き完了のためチャンネルを削除").complete();
            } catch (RuntimeException e) {
                logger.warning("退会専用チャンネルの削除に失敗しました (channelId=" + channel.getIdLong() + "): " + e.getMessage());
            }
        }, 5, TimeUnit.SECONDS);
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
