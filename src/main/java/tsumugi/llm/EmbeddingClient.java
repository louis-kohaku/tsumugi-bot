package tsumugi.llm;

/**
 * Lane方式のEmbedding呼び出しインタフェース。
 * tsumugi.memory.embedding.EmbeddingClient と役割は同じだが、
 * tsumugi.llm パッケージ内のLane系クラス（LaneEmbeddingClient/LaneLlmDispatcher）が
 * 依存する型をこちらに揃えるために用意している。
 *
 * LmStudioGatewayはこの両方のインタフェースを実装する
 * （メソッドシグネチャが同一のため、実装は1つで両方を満たせる）。
 */
@FunctionalInterface
public interface EmbeddingClient {
    /** @return embeddingベクトル。生成失敗時はnull。 */
    float[] embed(String text);
}
