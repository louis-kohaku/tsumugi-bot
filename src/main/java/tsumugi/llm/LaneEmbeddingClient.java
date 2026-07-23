package tsumugi.llm;

/**
 * 特定のLlmLaneに固定してLaneLlmDispatcherへ委譲するEmbeddingClient実装。
 * MemoryRetriever（CHAT）・MemoryConsolidator（HEAVY）等から通常のEmbeddingClientとして扱える。
 */
public final class LaneEmbeddingClient implements EmbeddingClient {

    private final LaneLlmDispatcher dispatcher;
    private final LlmLane lane;

    public LaneEmbeddingClient(LaneLlmDispatcher dispatcher, LlmLane lane) {
        this.dispatcher = dispatcher;
        this.lane = lane;
    }

    @Override
    public float[] embed(String text) {
        return dispatcher.embed(lane, text);
    }
}
