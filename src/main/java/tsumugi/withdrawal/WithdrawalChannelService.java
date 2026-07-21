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
 * 退会専用チャンネルの命名規則・権限設定を担うサービス。
 * initialsetup.InitialSetupChannelServiceと同じ命名規則（🌼｜チャンネル名）に揃える。
 *
 * 閲覧・投稿可能: 対象ユーザー、Bot自身、管理者のみ。@everyoneには非表示。
 */
public final class WithdrawalChannelService {

    private static final Logger logger = Logger.getLogger(WithdrawalChannelService.class.getName());

    private static final String CHANNEL_PREFIX = "🌼｜";
    private static final String WITHDRAWAL_CHANNEL_BASE_NAME = "退会";
    private static final String WITHDRAWAL_REQUEST_CHANNEL_NAME = "退会希望";

    public static final String CHOICE_PROMPT_MESSAGE = """
            退会手続きを開始します。
            今後、あなたに関するデータをどう扱うか選んでください。数字だけを送信してください。

            1: 匿名化して保持（あなたと紐付かない形の統計・研究データとして残します）
            2: 記名で保持（次回また参加された際に、これまでの記憶を引き継げるようにします）
            3: 削除（あなたに関する全データを削除します）
            """;

    public static final String WITHDRAWAL_REQUEST_STANDING_MESSAGE =
            "退会をご希望の方は、こちらに「退会」と入力してください。専用のチャンネルを作成します。";

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "withdrawal-channel-scheduler");
                t.setDaemon(true);
                return t;
            });

    /** 紬希が作成する全チャンネルの命名規則を統一するヘルパー（initialsetup側と同一ロジック）。 */
    public String buildChannelName(String category, String suffix) {
        String base = CHANNEL_PREFIX + category;
        if (suffix == null || suffix.isBlank()) return sanitize(base);
        return sanitize(base + "-" + suffix);
    }

    private String sanitize(String name) {
        String trimmed = name.strip();
        return trimmed.length() > 100 ? trimmed.substring(0, 100) : trimmed;
    }

    /** チャンネル名が「本人専用の」退会チャンネルかどうかの判定（🌼｜退会-ユーザー名）。 */
    public boolean isWithdrawalChannelName(String channelName) {
        return channelName != null && channelName.startsWith(CHANNEL_PREFIX + WITHDRAWAL_CHANNEL_BASE_NAME + "-");
    }

    /** チャンネル名が常設の「退会希望」チャンネルかどうかの判定。 */
    public boolean isWithdrawalRequestChannelName(String channelName) {
        return buildChannelName(WITHDRAWAL_REQUEST_CHANNEL_NAME, null).equals(channelName);
    }

    /** 退会希望チャンネルが無ければ作成する。何度呼んでも安全（冪等）。 */
    public TextChannel ensureWithdrawalRequestChannel(Guild guild) {
        String name = buildChannelName(WITHDRAWAL_REQUEST_CHANNEL_NAME, null);
        TextChannel existing = findChannelByName(guild, name);
        if (existing != null) {
            logger.info("退会希望チャンネルは既に存在するため作成をスキップしました: guildId=" + guild.getIdLong()
                    + " channelId=" + existing.getIdLong());
            return existing;
        }
        return createWithdrawalRequestChannel(guild, name);
    }

    private TextChannel createWithdrawalRequestChannel(Guild guild, String name) {
        TextChannel channel = guild.createTextChannel(name)
                .addPermissionOverride(guild.getPublicRole(),
                        List.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_HISTORY),
                        null)
                .addPermissionOverride(guild.getSelfMember(),
                        List.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MANAGE_CHANNEL),
                        null)
                .complete();
        channel.sendMessage(WITHDRAWAL_REQUEST_STANDING_MESSAGE).queue();
        logger.info("退会希望チャンネルを作成しました: guildId=" + guild.getIdLong());
        return channel;
    }

    /**
     * 5秒後に退会希望チャンネルを一度削除し、まっさらな状態で作り直す。
     * 「退会」発言など直近のやり取りを残さないためのリセット処理
     * （initialsetupの入室チャンネルと同じ方式）。
     */
    public void scheduleWithdrawalRequestChannelRecreate(Guild guild) {
        scheduler.schedule(() -> {
            try {
                String name = buildChannelName(WITHDRAWAL_REQUEST_CHANNEL_NAME, null);
                TextChannel existing = findChannelByName(guild, name);
                if (existing != null) {
                    existing.delete().reason("退会希望チャンネルのリセット").complete();
                }
                createWithdrawalRequestChannel(guild, name);
            } catch (RuntimeException e) {
                logger.warning("退会希望チャンネルの再作成に失敗しました (guildId=" + guild.getIdLong() + "): " + e.getMessage());
            }
        }, 5, TimeUnit.SECONDS);
    }

    private TextChannel findChannelByName(Guild guild, String name) {
        return guild.getTextChannelsByName(name, true).stream().findFirst().orElse(null);
    }

    /**
     * 対象ユーザー専用の退会チャンネルを作成し、3択の案内メッセージを送信する。
     * 閲覧可能: 対象ユーザー・Bot・管理者ロールのみ（@everyoneには非表示）。
     */
    public TextChannel createWithdrawalChannel(Guild guild, Member targetMember) {
        String channelName = buildChannelName(WITHDRAWAL_CHANNEL_BASE_NAME, targetMember.getEffectiveName());
        Member selfMember = guild.getSelfMember();

        TextChannel channel = guild.createTextChannel(channelName)
                .addPermissionOverride(guild.getPublicRole(), null, List.of(Permission.VIEW_CHANNEL))
                .addPermissionOverride(targetMember,
                        List.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_HISTORY),
                        null)
                .addPermissionOverride(selfMember,
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

        channel.sendMessage(CHOICE_PROMPT_MESSAGE).queue();

        logger.info("退会チャンネルを作成しました: name=" + channelName
                + " userId=" + targetMember.getIdLong() + " guildId=" + guild.getIdLong());
        return channel;
    }

    /** 退会確定後、後片付けとしてチャンネルを削除する（キック実行と合わせて呼ぶ想定）。 */
    public void deleteChannel(TextChannel channel) {
        if (channel == null) return;
        channel.delete().reason("退会手続き完了のためクリーンアップ").queue(
                success -> logger.info("退会チャンネルを削除しました: " + channel.getId()),
                failure -> logger.warning("退会チャンネルの削除に失敗しました: " + failure.getMessage())
        );
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
