package tsumugi.llm;

/**
 * LaneLlmDispatcherへ、固定のLlmLaneでembedを流し込む薄いアダプタ。
 * MemoryRetriever/MemoryConsolidator等、既存のEmbeddingClient利用側の
 * コードは一切変更せずに済むようにするためのもの。
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