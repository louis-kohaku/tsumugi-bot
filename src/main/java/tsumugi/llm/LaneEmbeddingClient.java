package tsumugi.llm;

/**
<<<<<<< HEAD
 * 特定のLlmLaneに固定してLaneLlmDispatcherへ委譲するEmbeddingClient実装。
 * MemoryRetriever（CHAT）・MemoryConsolidator（HEAVY）等から通常のEmbeddingClientとして扱える。
=======
 * LaneLlmDispatcherへ、固定のLlmLaneでembedを流し込む薄いアダプタ。
 * MemoryRetriever/MemoryConsolidator等、既存のEmbeddingClient利用側の
 * コードは一切変更せずに済むようにするためのもの。
>>>>>>> 845a43dc06155023d2c10e267d55ed61bb35cf5c
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
