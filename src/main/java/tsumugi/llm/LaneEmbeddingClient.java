package tsumugi.llm;

/**
 * LaneLlmDispatcherへ、固定のLlmLaneでembedを流し込む薄いアダプタ。
 * MemoryRetriever/MemoryConsolidator（tsumugi.memory.embedding.EmbeddingClient）と
 * tsumugi.llm系の両方のEmbeddingClientインタフェースを実装し、
 * どちらの利用側コードも変更せずに済むようにする。
 */
public final class LaneEmbeddingClient implements EmbeddingClient, tsumugi.memory.embedding.EmbeddingClient {

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