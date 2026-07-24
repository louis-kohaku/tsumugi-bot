package tsumugi.llm;

/**
<<<<<<< HEAD
 * 特定のLlmLaneに固定してLaneLlmDispatcherへ委譲するLlmClient実装。
 * ConversationEngine（CHAT）・DiarySummaryGenerator（DIARY）・EvidenceExtractor（HEAVY）等、
 * 呼び出し側はレーンの存在を意識せず通常のLlmClientとして扱える。
=======
 * LaneLlmDispatcherへ、固定のLlmLaneでcallを流し込む薄いアダプタ。
 * ConversationEngine/EvidenceExtractor/DiarySummaryGenerator等、
 * 既存のLlmClient利用側のコードは一切変更せずに済むようにするためのもの。
>>>>>>> 845a43dc06155023d2c10e267d55ed61bb35cf5c
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
