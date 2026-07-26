package tsumugi.diary;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * 日記関連チャンネルの命名規則・権限設定を一元管理する。
 * WithdrawalChannelService / InitialSetupChannelServiceと同じ方針
 * （🌼｜プレフィックス、対象ユーザー・Bot・管理者のみ閲覧可）。
 *
 * 要望部屋は「🌼｜要望」カテゴリ配下に、ユーザーごとの個人チャンネル
 * （🌼｜要望-ユーザー名）を配置する構成とする（InitialSetupChannelServiceの
 * 「紬希の庭」カテゴリと同じ構造）。
 *
 * 【修正】以前のensureRequestRoom()は、組み立てたチャンネル名とカテゴリ配下の
 * 既存チャンネル名を文字列一致で比較して「既存チャンネルか」を判定していたが、
 * Discordがチャンネル名を自動正規化（英字の小文字化等）するため、表示名に
 * アルファベット大文字を含むユーザー等でこの一致判定が常に失敗し、
 * 呼び出すたびに新規チャンネルが量産されてしまうバグがあった。
 *
 * 対応として、既存チャンネルの特定は呼び出し元（DiaryManager）が
 * DiaryRequestRoomRepositoryから読み出した「既知のチャンネルID」で行うように変更した。
 * このクラス自身は「そのIDのチャンネルが実在するか」をguild.getTextChannelById()で
 * 確認するだけになり、名前の正規化ルールには一切依存しない。
 */
public final class DiaryChannelService {

    private static final Logger logger = Logger.getLogger(DiaryChannelService.class.getName());

    private static final String CHANNEL_PREFIX = "🌼｜";
    private static final String REQUEST_CATEGORY_NAME = "要望";
    private static final String DIARY_ROOM_BASE_NAME = "diary";
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    public static final String REQUEST_ROOM_STANDING_MESSAGE =
            "ここはあなた専用の要望部屋です。紬への要望・設定変更・日記の開始（/日記）などに使ってください。";

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "diary-channel-scheduler");
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

    // ═══════════════════════════════════════
    //  要望カテゴリ（サーバーに1つだけ常設）
    // ═══════════════════════════════════════

    /** 「🌼｜要望」カテゴリが無ければ作成する。何度呼んでも安全（冪等）。 */
    public Category ensureRequestCategory(Guild guild) {
        String name = CHANNEL_PREFIX + REQUEST_CATEGORY_NAME;
        Category existing = guild.getCategoriesByName(name, true).stream().findFirst().orElse(null);
        if (existing != null) return existing;

        Category category = guild.createCategory(name)
                .addPermissionOverride(guild.getPublicRole(), null, List.of(Permission.VIEW_CHANNEL))
                .addPermissionOverride(guild.getSelfMember(),
                        List.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_HISTORY, Permission.MANAGE_CHANNEL),
                        null)
                .complete();
        logger.info("要望カテゴリを作成しました: guildId=" + guild.getIdLong());
        return category;
    }

    // ═══════════════════════════════════════
    //  要望部屋（ユーザー専用・常設・カテゴリ配下）
    // ═══════════════════════════════════════

    private String requestRoomName(String displayName) {
        return buildName(REQUEST_CATEGORY_NAME, displayName);
    }

    /** チャンネル名が要望カテゴリ配下の個人チャンネル命名規則に一致するか判定する（ルーティング判定専用。同一性の特定には使わない）。 */
    public boolean isRequestRoomName(String channelName) {
        return channelName != null && channelName.startsWith(CHANNEL_PREFIX + REQUEST_CATEGORY_NAME + "-");
    }

    /**
     * 対象ユーザー専用の要望部屋を、要望カテゴリ配下に作成する（既にあれば何もしない・冪等）。
     * 対象ユーザー・Bot・管理者ロールのみ閲覧可能（@everyoneには非表示）。
     *
     * @param knownChannelId 呼び出し元（DiaryRequestRoomRepository）に記録済みの、
     *                       このユーザーの要望部屋チャンネルID。未登録・不明ならnull。
     *                       nullでない場合はまずこのIDで実在確認し、実在すればそれを返す
     *                       （名前の一致判定は一切行わない）。
     */
    public TextChannel ensureRequestRoom(Guild guild, Member targetMember, String displayName, Long knownChannelId) {
        if (knownChannelId != null) {
            TextChannel existing = guild.getTextChannelById(knownChannelId);
            if (existing != null) return existing;
            logger.info("記録済みの要望部屋チャンネルが見つからなかったため、再作成します: userId="
                    + targetMember.getIdLong() + " knownChannelId=" + knownChannelId);
        }

        Category category = ensureRequestCategory(guild);
        String name = requestRoomName(displayName);
        return createRequestRoom(guild, category, targetMember, name);
    }

    private TextChannel createRequestRoom(Guild guild, Category category, Member targetMember, String name) {
        var self = guild.getSelfMember();

        var action = category.createTextChannel(name)
                .addPermissionOverride(guild.getPublicRole(), null, List.of(Permission.VIEW_CHANNEL))
                .addPermissionOverride(targetMember,
                        List.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_HISTORY),
                        null)
                .addPermissionOverride(self,
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

        channel.sendMessage(REQUEST_ROOM_STANDING_MESSAGE).queue();
        logger.info("要望部屋を作成しました: name=" + name + " userId=" + targetMember.getIdLong()
                + " guildId=" + guild.getIdLong());
        return channel;
    }

    // ═══════════════════════════════════════
    //  プライベート日記部屋（1セッション限りの一時チャンネル。要望カテゴリの外に作る）
    // ═══════════════════════════════════════

    public boolean isDiaryRoomName(String channelName) {
        return channelName != null && channelName.startsWith(CHANNEL_PREFIX + DIARY_ROOM_BASE_NAME + "-");
    }

    /** #diary-ユーザー名-日付 形式のプライベートチャンネルを作成する。 */
    public TextChannel createDiaryRoom(Guild guild, Member member, String displayName) {
        String suffix = displayName + "-" + LocalDate.now().format(DATE_FORMAT);
        String name = buildName(DIARY_ROOM_BASE_NAME, suffix);
        var self = guild.getSelfMember();

        TextChannel channel = guild.createTextChannel(name)
                .addPermissionOverride(guild.getPublicRole(), null, List.of(Permission.VIEW_CHANNEL))
                .addPermissionOverride(member,
                        List.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_HISTORY),
                        null)
                .addPermissionOverride(self,
                        List.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_HISTORY, Permission.MANAGE_CHANNEL),
                        null)
                .complete();

        logger.info("プライベート日記部屋を作成しました: name=" + name + " userId=" + member.getIdLong());
        return channel;
    }

    public void postMessage(TextChannel channel, String message) {
        channel.sendMessage(message).queue(
                success -> {},
                failure -> logger.warning("日記メッセージの送信に失敗しました: " + failure.getMessage())
        );
    }

    /** 1分後にこのチャンネル自体を削除する。 */
    public void scheduleDiaryRoomDelete(TextChannel channel) {
        scheduler.schedule(() -> {
            try {
                channel.delete().reason("日記セッション完了のためチャンネルを削除").complete();
            } catch (RuntimeException e) {
                logger.warning("日記部屋の削除に失敗しました (channelId=" + channel.getIdLong() + "): " + e.getMessage());
            }
        }, 1, TimeUnit.MINUTES);
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
