package tsumugi.broadcast;

import tsumugi.llm.LlmClient;

import java.util.logging.Logger;

/**
 * お知らせ原文をLLMでチェックし、不自然な箇所があれば修正した文面を返す。
 * DiarySummaryGenerator/EvidenceExtractorと同じ構造でLlmClientを利用する。
 *
 * 出力は本文のみを期待する（JSON等の構造化は行わない。配信文はそのまま
 * ユーザーへ表示されるため、余計な前置き・Markdown記法が混ざらないよう
 * システムプロンプト側で強く指示する）。
 *
 * 【変更点】校正チェックの最大トークン数（旧: 定数 MAX_TOKENS = 800 のハードコード）を、
 * コンストラクタ引数として外部（AppConfig / .envの LLM_MAX_TOKENS_BROADCAST）から注入する形に変更した。
 */
public final class BroadcastReviewer {

    private static final Logger logger = Logger.getLogger(BroadcastReviewer.class.getName());

    private static final String SYSTEM_PROMPT = """
        あなたは「紬希（つむぎ）」というDiscord botの運営者が全ユーザーへ送る
        お知らせ文をチェックする校正アシスタントです。

        渡された文章について、日本語として不自然な表現・誤字脱字・分かりにくい言い回しが
        あれば自然な文章に直してください。問題が無ければ元の文章をそのまま返してください。

        出力は最終的なお知らせ文の本文のみとし、説明文・前置き・Markdownのコードフェンス・
        見出し等は一切含めないでください。元の文章の意図・情報・絵文字の雰囲気はできる限り保ってください。
        """;

    private static final double TEMPERATURE = 0.3;

    private final LlmClient llmClient;

    /** 校正チェックに使う最大トークン数。AppConfig.llmMaxTokensBroadcast（.envの LLM_MAX_TOKENS_BROADCAST）から注入される。 */
    private final int maxTokens;

    public BroadcastReviewer(LlmClient llmClient, int maxTokens) {
        this.llmClient = llmClient;
        this.maxTokens = maxTokens;
    }

    /**
     * 原文をチェックし、配信案となる文面を返す。
     * LLM呼び出し失敗時は元の文章をそのまま返す（配信自体を止めないため）。
     */
    public String review(String rawContent) {
        if (rawContent == null || rawContent.isBlank()) return rawContent;

        String result = llmClient.call(SYSTEM_PROMPT, rawContent, maxTokens, TEMPERATURE);
        if (result == null || result.isBlank()) {
            logger.warning("お知らせ文チェックのLLM応答が空だったため、元の文章をそのまま使用します。");
            return rawContent.strip();
        }
        return stripCodeFence(result).strip();
    }

    /** LLMが```で包んで返してくることがあるための保険（EvidenceExtractorと同じ処理）。 */
    private String stripCodeFence(String raw) {
        String trimmed = raw.strip();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline != -1) trimmed = trimmed.substring(firstNewline + 1);
            if (trimmed.endsWith("```")) {
                trimmed = trimmed.substring(0, trimmed.length() - 3);
            }
        }
        return trimmed.strip();
    }
}
