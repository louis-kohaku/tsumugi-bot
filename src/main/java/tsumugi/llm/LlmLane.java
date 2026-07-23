package tsumugi.llm;

/**
 * LLM呼び出しの優先度レーン。
 *
 *  - CHAT : 通常会話の応答生成・記憶検索。最優先の即時レーン。
 *  - DIARY: 日記の総評生成。CHATと同格の即時レーン。
 *  - HEAVY: Evidence抽出（性格・感情分析）等。CHAT/DIARYが無い時にのみ着手する。
 *
 * 実際の優先度制御はLaneLlmDispatcherが行う。
 */
public enum LlmLane {
    CHAT,
    DIARY,
    HEAVY
}
