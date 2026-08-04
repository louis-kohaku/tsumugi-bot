package tsumugi.diary;

import tsumugi.diary.model.DiaryRecord;
import tsumugi.llm.LlmClient;

import java.util.Map;
import java.util.logging.Logger;

/**
 * DiaryRecordの内容から紬の総評（daily_summary）を生成する。
 * EvidenceExtractorと同じ構造でLlmClientを利用する。
 *
 * 【変更点】総評生成の最大トークン数（旧: 定数 MAX_TOKENS = 600 のハードコード）を、
 * コンストラクタ引数として外部（AppConfig / .envの LLM_MAX_TOKENS_DIARY）から注入する形に変更した。
 */
public final class DiarySummaryGenerator {

    private static final Logger logger = Logger.getLogger(DiarySummaryGenerator.class.getName());

    private static final String SYSTEM_PROMPT = """
        あなたは「紬（つむぎ）」という名前のAIコンパニオンです。
        ユーザーが記録した今日一日の日記データを踏まえ、以下の4点を含む総評メッセージを
        親しみやすく温かい口調で生成してください（絵文字を適度に使ってよい）。

        1. 一日の流れの簡潔なまとめ
        2. 「できたこと」への肯定・称賛
        3. 「よくなかったこと」への振り返りコメント
           ※ユーザーを責めない。気づき・振り返り・明日への小さな工夫として扱うこと。
        4. 「明日挑戦すること」への前向きな応援

        出力は本文のみとし、見出しやMarkdown記法は使わないでください。
        """;

    private static final double TEMPERATURE = 0.7;
    private static final String FALLBACK_SUMMARY =
            "今日も一日お疲れさまでした😊 記録、ありがとうございました。";

    private final LlmClient llmClient;

    /** 総評生成に使う最大トークン数。AppConfig.llmMaxTokensDiary（.envの LLM_MAX_TOKENS_DIARY）から注入される。 */
    private final int maxTokens;

    public DiarySummaryGenerator(LlmClient llmClient, int maxTokens) {
        this.llmClient = llmClient;
        this.maxTokens = maxTokens;
    }

    public String generate(DiaryRecord record) {
        String userPrompt = buildUserPrompt(record);
        String summary = llmClient.call(SYSTEM_PROMPT, userPrompt, maxTokens, TEMPERATURE);
        if (summary == null || summary.isBlank()) {
            logger.warning("日記総評の生成に失敗しました (userId=" + record.userId + ")。フォールバック文言を使用します。");
            return FALLBACK_SUMMARY;
        }
        return summary.strip();
    }

    private String buildUserPrompt(DiaryRecord record) {
        StringBuilder sb = new StringBuilder();
        sb.append("起床時間: ").append(record.wakeUpTime).append("\n\n");
        sb.append("一日の流れ:\n");
        for (Map.Entry<String, String> entry : record.timeline.entrySet()) {
            sb.append("- ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
        }
        sb.append("\nできたこと: ").append(record.achievements);
        sb.append("\nよくなかったこと: ").append(record.badPoints);
        sb.append("\n明日挑戦すること: ").append(record.tomorrowChallenge);
        return sb.toString();
    }
}
