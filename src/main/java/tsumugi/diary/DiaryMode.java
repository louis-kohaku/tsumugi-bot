package tsumugi.diary;

/**
 * 日記機能のモード。
 * 現時点ではSTANDARDのみ実装。他モードは将来DiaryFlowの実装クラスを追加する形で拡張する。
 */
public enum DiaryMode {
    STANDARD
    // TODO: 将来 LIGHT / DEEP 等のモードをここに追加し、
    //       DiarySessionManager.flowFor(mode) にハンドラを紐付ける想定。
}
