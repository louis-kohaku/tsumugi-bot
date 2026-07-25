package tsumugi.initialsetup;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import tsumugi.initialsetup.model.InitialSetupRecord;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * 紬希が作成するチャンネルの命名規則・権限設定を一元管理するサービス。
 *
 * 命名規則: "🌼｜チャンネル名"（例: 🌼｜入室, 🌼｜紬希の庭-ユーザー名, 🌼｜確認-ユーザー名）
 *
 * 扱うチャンネル構成:
 *  - 入室チャンネル: サーバーに1つだけ常設。@everyoneに見える固定案内文を常に表示し、
 *    ここに「読んでほしい名前」が投稿されたことをトリガーに初期設定を進める。
 *    使用後は一度削除→再作成することで、常にまっさらな状態を保つ。
 *  - 利用規約確認-ユーザー名: 名前入力直後、本人専用に作成する一時チャンネル。
 *    利用規約への同意可否をここで確認する。
 *  - 紬希の庭-ユーザー名（カテゴリ）: 本人専用。配下に 雑談部屋/ログ部屋/お知らせ部屋 を作成する。
 *    対象ユーザー・Bot・管理者のみ閲覧可能。退室時は即時削除する。
 *  - 確認-ユーザー名: 退会（記名保持選択）済みユーザーが再入室した際、
 *    以前の記憶を引き継ぐかどうかを1対1で確認するための一時チャンネル。
 */
public final class InitialSetupChannelService {

    private static final Logger logger = Logger.getLogger(InitialSetupChannelService.class.getName());

    private static final String CHANNEL_PREFIX = "🌼｜";
    private static final String ENTRY_CHANNEL_NAME = "入室";
    private static final String GARDEN_CATEGORY_BASE_NAME = "紬希の庭";
    private static final String REJOIN_CONFIRM_BASE_NAME = "確認";
    private static final String CONSENT_BASE_NAME = "利用規約確認";

    public static final String ENTRY_CHANNEL_STANDING_MESSAGE =
            "入っていただきありがとうございます。こちらに読んでほしい名前を記入してください。";

    public static final String REJOIN_CONFIRM_STANDING_MESSAGE_TEMPLATE =
            "おかえりなさい。以前「%s」として、これまでの記憶を引き継ぐ設定で退室されています。"
            + "今回、以前の記憶を引き継ぎますか？「はい」または「いいえ」でお答えください。";

    /** %sには対象ユーザーの表示名が入る。 */
    public static final String CONSENT_STANDING_MESSAGE_TEMPLATE =
            "%sさん、ご入室ありがとうございます。"
            + "紬希をご利用いただく前に、利用規約への同意をお願いしています。"
            + "同意される場合は「同意する」、されない場合は「同意しない」とお送りください。"
            + "（1分以内にご回答がない場合は、同意なしとして扱われます）";

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "initialsetup-channel-scheduler");
                t.setDaemon(true);
                return t;
            });

    /** 紬希が作成する全チャンネルの命名規則を統一するヘルパー。 */
    public String buildChannelName(String category, String suffix) {
        String base = CHANNEL_PREFIX + category;
        if (suffix == null || suffix.isBlank()) return sanitize(base);
        return sanitize(base + "-" + suffix);
    }

    private String sanitize(String name) {
        String trimmed = name.strip();
        return trimmed.length() > 100 ? trimmed.substring(0, 100) : trimmed;
    }

    // ═══════════════════════════════════════
    //  入室チャンネル
    // ═══════════════════════════════════════

    /** 入室チャンネルが無ければ作成する。サーバー起動時・参加時など、何度呼んでも安全（冪等）。 */
    public TextChannel ensureEntryChannel(Guild guild) {
        String name = buildChannelName(ENTRY_CHANNEL_NAME, null);
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
        channel.sendMessage(ENTRY_CHANNEL_STANDING_MESSAGE).queue();
        logger.info("入室チャンネルを作成しました: guildId=" + guild.getIdLong());
        return channel;
    }

    /**
     * 5秒後に入室チャンネルを一度削除し、まっさらな状態で作り直す。
     * 直近のやり取り（前のユーザーの入力メッセージ等）を残さないためのリセット処理。
     */
    public void scheduleEntryChannelRecreate(Guild guild) {
        scheduler.schedule(() -> {
            try {
                String name = buildChannelName(ENTRY_CHANNEL_NAME, null);
                TextChannel existing = findChannelByName(guild, name);
                if (existing != null) {
                    existing.delete().reason("入室チャンネルのリセット").complete();
                }
                createEntryChannel(guild, name);
            } catch (RuntimeException e) {
                logger.warning("入室チャンネルの再作成に失敗しました (guildId=" + guild.getIdLong() + "): " + e.getMessage());
            }
        }, 5, TimeUnit.SECONDS);
    }

    private TextChannel findChannelByName(Guild guild, String name) {
        return guild.getTextChannelsByName(name, true).stream().findFirst().orElse(null);
    }

    // ═══════════════════════════════════════
    //  利用規約同意チャンネル（本人専用・一時）
    // ═══════════════════════════════════════

    /**
     * 名前入力直後、利用規約への同意可否を確認するための専用チャンネルを作成する。
     * 対象ユーザー・Bot・管理者ロールのみ閲覧可能（@everyoneには非表示）。
     */
    public TextChannel createConsentChannel(Guild guild, Member targetMember, String displayName) {
        String channelName = buildChannelName(CONSENT_BASE_NAME, targetMember.getEffectiveName());
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

        channel.sendMessage(String.format(CONSENT_STANDING_MESSAGE_TEMPLATE, displayName)).queue();

        logger.info("利用規約同意チャンネルを作成しました: userId=" + targetMember.getIdLong() + " guildId=" + guild.getIdLong());
        return channel;
    }

    // ═══════════════════════════════════════
    //  紬希の庭（本人専用のメインチャンネル群）
    // ═══════════════════════════════════════

    public static final class GardenChannels {
        public final long categoryId;
        public final long chatChannelId;
        public final long logChannelId;
        public final long announceChannelId;

        public GardenChannels(long categoryId, long chatChannelId, long logChannelId, long announceChannelId) {
            this.categoryId = categoryId;
            this.chatChannelId = chatChannelId;
            this.logChannelId = logChannelId;
            this.announceChannelId = announceChannelId;
        }
    }

    /**
     * 「🌼｜紬希の庭-ユーザー名」カテゴリと、配下の 雑談部屋/ログ部屋/お知らせ部屋 を作成する。
     * 対象ユーザー・Bot・管理者ロールのみ閲覧可能（@everyoneには非表示）。
     */
    public GardenChannels createGardenChannels(Guild guild, Member targetMember, String displayName) {
        String categoryName = buildChannelName(GARDEN_CATEGORY_BASE_NAME, displayName);
        Member selfMember = guild.getSelfMember();

        Category category = guild.createCategory(categoryName)
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
                .forEach(adminRole -> category.getManager()
                        .putRolePermissionOverride(adminRole.getIdLong(),
                                List.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_HISTORY),
                                null)
                        .queue());

        TextChannel chat = createChildChannel(category, "雑談部屋", targetMember, selfMember);
        TextChannel log = createChildChannel(category, "ログ部屋", targetMember, selfMember);
        TextChannel announce = createChildChannel(category, "お知らせ部屋", targetMember, selfMember);

        logger.info("紬希の庭を作成しました: userId=" + targetMember.getIdLong()
                + " guildId=" + guild.getIdLong() + " category=" + categoryName);

        return new GardenChannels(category.getIdLong(), chat.getIdLong(), log.getIdLong(), announce.getIdLong());
    }

    /**
     * カテゴリ配下にテキストチャンネルを作成し、カテゴリと同じ権限（@everyone非表示、
     * 対象ユーザー・Bot・管理者ロールのみ閲覧可）を明示的に付け直す。
     * このJDAバージョンのChannelActionにはsetSync相当が無いため、権限は都度複製する。
     */
    private TextChannel createChildChannel(Category category, String name, Member targetMember, Member selfMember) {
        Guild guild = category.getGuild();

        var action = category.createTextChannel(name)
                .addPermissionOverride(guild.getPublicRole(), null, List.of(Permission.VIEW_CHANNEL))
                .addPermissionOverride(targetMember,
                        List.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_HISTORY),
                        null)
                .addPermissionOverride(selfMember,
                        List.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_HISTORY, Permission.MANAGE_CHANNEL),
                        null);

        TextChannel channel = action.complete();

        guild.getRoles().stream()
                .filter(role -> role.hasPermission(Permission.ADMINISTRATOR))
                .forEach(adminRole -> channel.getManager()
                        .putRolePermissionOverride(adminRole.getIdLong(),
                                List.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_HISTORY),
                                null)
                        .queue());

        return channel;
    }

    /**
     * 退室時に「紬希の庭」（カテゴリ＋配下3チャンネル）を即時削除する。
     * recordに保存されているIDを元に削除するため、既にDiscord側で手動削除済み等の
     * ケースでも例外にせず、見つからないものは静かにスキップする。
     */
    public void deleteGardenChannels(Guild guild, InitialSetupRecord record) {
        deleteChannelIfExists(guild, record.chatChannelId, "雑談部屋");
        deleteChannelIfExists(guild, record.logChannelId, "ログ部屋");
        deleteChannelIfExists(guild, record.announceChannelId, "お知らせ部屋");
        deleteCategoryIfExists(guild, record.gardenCategoryId);
    }

    private void deleteChannelIfExists(Guild guild, Long channelId, String label) {
        if (channelId == null) return;
        TextChannel channel = guild.getTextChannelById(channelId);
        if (channel == null) return;
        channel.delete().reason("退室に伴う紬希の庭クリーンアップ").queue(
                success -> logger.info("庭チャンネルを削除しました: " + label + " id=" + channelId),
                failure -> logger.warning("庭チャンネルの削除に失敗しました: " + label + " id=" + channelId
                        + " : " + failure.getMessage())
        );
    }

    private void deleteCategoryIfExists(Guild guild, Long categoryId) {
        if (categoryId == null) return;
        Category category = guild.getCategoryById(categoryId);
        if (category == null) return;
        category.delete().reason("退室に伴う紬希の庭クリーンアップ").queue(
                success -> logger.info("庭カテゴリを削除しました: id=" + categoryId),
                failure -> logger.warning("庭カテゴリの削除に失敗しました: id=" + categoryId + " : " + failure.getMessage())
        );
    }

    // ═══════════════════════════════════════
    //  引継ぎ確認チャンネル（退会時に記名保持を選んだユーザーの再入室時）
    // ═══════════════════════════════════════

    /**
     * 対象ユーザー専用の引継ぎ確認チャンネルを作成し、確認メッセージを送信する。
     * 閲覧可能: 対象ユーザー・Bot・管理者ロールのみ（@everyoneには非表示）。
     */
    public TextChannel createRejoinConfirmChannel(Guild guild, Member targetMember, String previousDisplayName) {
        String channelName = buildChannelName(REJOIN_CONFIRM_BASE_NAME, targetMember.getEffectiveName());
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

        channel.sendMessage(String.format(REJOIN_CONFIRM_STANDING_MESSAGE_TEMPLATE, previousDisplayName)).queue();

        logger.info("引継ぎ確認チャンネルを作成しました: name=" + channelName
                + " userId=" + targetMember.getIdLong() + " guildId=" + guild.getIdLong());
        return channel;
    }

    /** 引継ぎ確認完了後、後片付けとしてチャンネルを削除する。同意チャンネル削除にも流用する。 */
    public void deleteChannel(TextChannel channel) {
        if (channel == null) return;
        channel.delete().reason("手続き完了のためクリーンアップ").queue(
                success -> logger.info("チャンネルを削除しました: " + channel.getId()),
                failure -> logger.warning("チャンネルの削除に失敗しました: " + failure.getMessage())
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

    // ═══════════════════════════════════════
    //  管理者専用チャンネル（入退室ログ等）
    // ═══════════════════════════════════════

    private static final String ADMIN_LOG_CHANNEL_NAME = "管理者ログ";

    /** 管理者専用ログチャンネルが無ければ作成する。何度呼んでも安全（冪等）。 */
    public TextChannel ensureAdminLogChannel(Guild guild) {
        String name = buildChannelName(ADMIN_LOG_CHANNEL_NAME, null);
        TextChannel existing = findChannelByName(guild, name);
        if (existing != null) return existing;
        return createAdminLogChannel(guild, name);
    }

    /**
     * 管理者・Bot以外は閲覧不可（@everyone非表示、対象ユーザー用のオーバーライドは付与しない）。
     * 参加/退出などの記録を投稿する用途を想定。
     */
    private TextChannel createAdminLogChannel(Guild guild, String name) {
        Member selfMember = guild.getSelfMember();

        TextChannel channel = guild.createTextChannel(name)
                .addPermissionOverride(guild.getPublicRole(), null, List.of(Permission.VIEW_CHANNEL))
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

        logger.info("管理者ログチャンネルを作成しました: guildId=" + guild.getIdLong());
        return channel;
    }

    /** 管理者ログチャンネルへメッセージを送る。無ければ作成してから送る。 */
    public void postAdminLog(Guild guild, String message) {
        TextChannel channel = ensureAdminLogChannel(guild);
        channel.sendMessage(message).queue(
                success -> {},
                failure -> logger.warning("管理者ログの送信に失敗しました (guildId=" + guild.getIdLong() + "): " + failure.getMessage())
        );
    }
}
