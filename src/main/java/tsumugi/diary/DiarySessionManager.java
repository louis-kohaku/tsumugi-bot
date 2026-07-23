package tsumugi.diary;

import java.time.LocalTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 日記セッションの開始・入力処理・進行管理を担う。
 * WithdrawalService/InitialSetupServiceに相当する中核クラスだが、
 * 状態はインメモリのみで管理する（DBに永続化しない設計。詳細はREADME_DIARY.md参照）。
 */
public final class DiarySessionManager {

    private static final Logger logger = Logger.getLogger(DiarySessionManager.class.getName());

    private static final Pattern WAKE_KEYWORD = Pattern.compile("起床|起きた|起きる|目覚めた");
    private static final Pattern SLEEP_KEYWORD = Pattern.compile("就寝|寝た|寝る|睡眠|布団");

    // 「6:30」「7時」「午前7時」等を拾う簡易パターン
    private static final Pattern TIME_COLON = Pattern.compile("(\\d{1,2}):(\\d{2})");
    private static final Pattern TIME_HOUR_ONLY = Pattern.compile("(午前|午後)?\\s*(\\d{1,2})\\s*時");

    private final Map<Long, DiarySession> sessions = new ConcurrentHashMap<>();
    private final Map<DiaryMode, DiaryFlow> flows = new ConcurrentHashMap<>();

    public DiarySessionManager() {
        register(new StandardDiaryFlow());
        // TODO: 将来モード追加時はここに register(new XxxDiaryFlow()); を足すだけでよい
    }

    public void register(DiaryFlow flow) {
        flows.put(flow.mode(), flow);
    }

    public boolean hasActiveSession(long userId) {
        return sessions.containsKey(userId);
    }

    public DiarySession getSession(long userId) {
        return sessions.get(userId);
    }

    /** /日記コマンド受付時に呼ぶ。スタンダードモードで新規セッションを開始する。 */
    public DiarySession startSession(long userId, long channelId, DiaryMode mode) {
        DiarySession session = new DiarySession(userId, mode);
        session.channelId = channelId;
        sessions.put(userId, session);
        logger.info("日記セッションを開始しました: userId=" + userId + " mode=" + mode);
        return session;
    }

    public void endSession(long userId) {
        sessions.remove(userId);
    }

    private DiaryFlow flowFor(DiarySession session) {
        DiaryFlow flow = flows.get(session.mode);
        if (flow == null) throw new IllegalStateException("未対応のDiaryModeです: " + session.mode);
        return flow;
    }

    public String currentPrompt(DiarySession session) {
        return flowFor(session).nextPrompt(session);
    }

    /**
     * ユーザー入力を現在の状態に応じて処理し、次に紬が送るべきメッセージを返す。
     * セッションが完了に達した場合はnullを返す（呼び出し側でDiaryServiceによる総評生成へ進める）。
     */
    public String handleInput(DiarySession session, String rawText) {
        String text = rawText == null ? "" : rawText.strip();
        DiaryFlow flow = flowFor(session);

        switch (session.state) {
            case WAITING_WAKE_TIME -> {
                LocalTime time = parseTime(text);
                if (time == null) {
                    return "時間の形式が読み取れませんでした。「6:30」「7時」のように送ってください。";
                }
                session.wakeUpTime = time;
                flow.initializeTimelineSlots(session);
                session.state = session.pendingSlots.isEmpty()
                        ? DiaryState.WAITING_ACHIEVEMENTS
                        : DiaryState.WAITING_TIMELINE;
                return flow.nextPrompt(session);
            }
            case WAITING_TIMELINE -> {
                if (SLEEP_KEYWORD.matcher(text).find()) {
                    session.pendingSlots.clear();
                    session.state = DiaryState.WAITING_ACHIEVEMENTS;
                    return "今日の一日の流れの記録はここまでです😊\n\n" + flow.nextPrompt(session);
                }
                String slot = session.pendingSlots.pollFirst();
                if (slot != null) {
                    session.timeline.put(slot, text);
                }
                if (session.pendingSlots.isEmpty()) {
                    session.state = DiaryState.WAITING_ACHIEVEMENTS;
                }
                return flow.nextPrompt(session);
            }
            case WAITING_ACHIEVEMENTS -> {
                session.achievements = text;
                session.state = DiaryState.WAITING_BAD_POINTS;
                return flow.nextPrompt(session);
            }
            case WAITING_BAD_POINTS -> {
                session.badPoints = text;
                session.state = DiaryState.WAITING_TOMORROW_CHALLENGE;
                return flow.nextPrompt(session);
            }
            case WAITING_TOMORROW_CHALLENGE -> {
                session.tomorrowChallenge = text;
                session.state = DiaryState.GENERATING_SUMMARY;
                return null; // 呼び出し側（DiaryManager）がここでDiaryServiceに総評生成を依頼する
            }
            default -> {
                return null;
            }
        }
    }

    private LocalTime parseTime(String text) {
        Matcher colon = TIME_COLON.matcher(text);
        if (colon.find()) {
            try {
                return LocalTime.of(Integer.parseInt(colon.group(1)), Integer.parseInt(colon.group(2)));
            } catch (NumberFormatException | java.time.DateTimeException ignored) {
                // フォールスルーして次のパターンを試す
            }
        }
        Matcher hourOnly = TIME_HOUR_ONLY.matcher(text);
        if (hourOnly.find()) {
            try {
                int hour = Integer.parseInt(hourOnly.group(2));
                if ("午後".equals(hourOnly.group(1)) && hour < 12) hour += 12;
                if (hour == 24) hour = 0;
                return LocalTime.of(hour, 0);
            } catch (NumberFormatException | java.time.DateTimeException ignored) {
                return null;
            }
        }
        return null;
    }
}
