package tsumugi.llm;

/**
 * 会話用LLM呼び出しのインタフェース。EmbeddingClientと対になる存在。
 * すみれのDiaryManager.LmStudioClient / callLmStudioSingleShotに相当。
 */
@FunctionalInterface
public interface LlmClient {
    /** @return AIの応答文字列。呼び出し失敗時はnull。 */
    String call(String systemPrompt, String userPrompt, int maxTokens, double temperature);
}
