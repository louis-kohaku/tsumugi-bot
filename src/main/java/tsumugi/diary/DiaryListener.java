package tsumugi.diary;

import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;

/**
 * 日記機能に関するJDAイベントだけを受け取る薄いリスナー。
 * 実処理はDiaryManagerに委譲する（DiscordAdapter/WithdrawalListenerと同じ方針）。
 *
 * 監視対象:
 *  - 要望部屋（🌼｜要望-ユーザー名）でのスラッシュコマンド /日記
 *  - プライベート日記部屋（🌼｜diary-ユーザー名-日付）でのメッセージ投稿
 */
public final class DiaryListener extends ListenerAdapter {

    public static final String DIARY_COMMAND_NAME = "日記";

    private final DiaryManager manager;

    public DiaryListener(DiaryManager manager) {
        this.manager = manager;
    }

    /**
     * JDABuilder.build()後、jda.updateCommands().addCommands(...) から呼ぶ想定のコマンド定義。
     * TsumugiApplication側で以下のように登録する:
     *   jda.updateCommands().addCommands(DiaryListener.commandData()).queue();
     */
    public static SlashCommandData commandData() {
        return Commands.slash(DIARY_COMMAND_NAME, "今日の日記セッションを開始します");
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!DIARY_COMMAND_NAME.equals(event.getName())) return;
        if (!event.isFromGuild()) return;

        Member member = event.getMember();
        if (member == null || member.getUser().isBot()) return;

        TextChannel requestRoom = manager.getOrCreateRequestRoom(member);
        if (event.getChannel().getIdLong() != requestRoom.getIdLong()) {
            event.reply("日記コマンドは要望部屋（" + requestRoom.getAsMention() + "）でお使いください。")
                    .setEphemeral(true).queue();
            return;
        }

        event.reply("日記セッションを開始します📔").setEphemeral(true).queue();
        manager.handleDiaryCommand(member, requestRoom);
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (event.getAuthor().isBot()) return;
        if (!event.isFromGuild()) return;

        Member member = event.getMember();
        if (member == null) return;

        String channelName = event.getChannel().getName();
        if (!manager.isDiaryRoomName(channelName)) return;
        if (!(event.getChannel() instanceof TextChannel textChannel)) return;

        String text = event.getMessage().getContentDisplay();
        manager.handleDiaryRoomMessage(member, text, textChannel);
    }
}
