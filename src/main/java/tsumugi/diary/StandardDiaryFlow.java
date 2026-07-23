package tsumugi.diary;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/** スタンダードモードのフロー実装。2時間単位のタイムライン質問を生成する。 */
public final class StandardDiaryFlow implements DiaryFlow {

    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");

    @Override
    public DiaryMode mode() {
        return DiaryMode.STANDARD;
    }

    @Override
    public void initializeTimelineSlots(DiarySession session) {
        session.pendingSlots.clear();
        LocalTime cursor = session.wakeUpTime;
        // 起床時刻から2時間刻みで24時（またはそれ以前の就寝検出）まで積む
        for (int i = 0; i < 12; i++) { // 最大24時間分、就寝検出があれば途中で打ち切られる
            LocalTime slotEnd = cursor.plusHours(2);
            String label = cursor.format(HHMM) + "-" + slotEnd.format(HHMM);
            session.pendingSlots.addLast(label);
            cursor = slotEnd;
            if (cursor.equals(LocalTime.MIDNIGHT)) break; // 日をまたいだら終了
        }
    }

    @Override
    public String nextPrompt(DiarySession session) {
        return switch (session.state) {
            case WAITING_WAKE_TIME -> "今日の記録を始めるために、起きた時間を教えてください😊";
            case WAITING_TIMELINE -> {
                String slot = session.pendingSlots.peekFirst();
                yield slot == null ? null
                        : "この時間（" + slot + "）は何をしていましたか？\n単語だけでも大丈夫です😊";
            }
            case WAITING_ACHIEVEMENTS -> "今日一日を通してできたことは何ですか？\n小さなことでも大丈夫です😊";
            case WAITING_BAD_POINTS -> "今日一日で、よくなかったことや気になったことはありますか？\n"
                    + "なければ「なし」でも大丈夫です😊";
            case WAITING_TOMORROW_CHALLENGE -> "明日挑戦してみたいことを教えてください😊";
            default -> null;
        };
    }
}
