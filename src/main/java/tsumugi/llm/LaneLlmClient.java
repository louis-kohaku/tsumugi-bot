package tsumugi.llm;

/**
 * LaneLlmDispatcherへ、固定のLlmLaneでcallを流し込む薄いアダプタ。
 * ConversationEngine/EvidenceExtractor/DiarySummaryGenerator等、
 * 既存のLlmClient利用側のコードは一切変更せずに済むようにするためのもの。
 */
public final class LaneLlmClient implements LlmClient {

    private final LaneLlmDispatcher dispatcher;
    private final LlmLane lane;

    public LaneLlmClient(LaneLlmDispatcher dispatcher, LlmLane lane) {
        this.dispatcher = dispatcher;
        this.lane = lane;
    }

    @Override
    public String call(String systemPrompt, String userPrompt, int maxTokens, double temperature) {
        return dispatcher.call(lane, systemPrompt, userPrompt, maxTokens, temperature);
    }
}