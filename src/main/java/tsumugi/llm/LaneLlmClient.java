package tsumugi.llm;

/**
 * 特定のLlmLaneに固定してLaneLlmDispatcherへ委譲するLlmClient実装。
 * ConversationEngine（CHAT）・DiarySummaryGenerator（DIARY）・EvidenceExtractor（HEAVY）等、
 * 呼び出し側はレーンの存在を意識せず通常のLlmClientとして扱える。
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
