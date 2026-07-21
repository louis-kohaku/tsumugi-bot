package tsumugi.memory.embedding;

/**
 * Evidenceのcontentをベクトル化するためのインタフェース。LlmClientと対になる存在。
 * LmStudioGatewayがLlmClientと合わせて実装する。
 */
@FunctionalInterface
public interface EmbeddingClient {
    /** @return embeddingベクトル。生成失敗時はnull。 */
    float[] embed(String text);
}
