package tsumugi.diary;

/**
 * モード別の質問フローを表す抽象。
 * スタンダードモード以外（ライト版・詳細版など）を追加する際は、
 * この実装クラスを追加してDiarySessionManagerに登録するだけで拡張できる。
 */
public interface DiaryFlow {

    DiaryMode mode();

    /** 起床時間確定後、Step2用の質問スロットを初期化する。 */
    void initializeTimelineSlots(DiarySession session);

    /** 次に紬が送るべき質問文を返す。フロー完了時はnull。 */
    String nextPrompt(DiarySession session);
}
