package tsumugi.memory.extract;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import tsumugi.core.model.TsumugiModel.Evidence;
import tsumugi.core.model.TsumugiModel.EvidenceCategory;
import tsumugi.core.model.TsumugiModel.Polarity;
import tsumugi.llm.LlmClient;
import tsumugi.memory.consolidate.MemoryConsolidator;

import java.util.List;
import java.util.logging.Logger;

/**
 * 1ユーザー発話からEvidence（性格・価値観・習慣等の手がかり）をLLMで抽出し、
 * MemoryConsolidatorへ引き渡す層。
 *
 * 抽出結果は「何もない」ことの方が多い（雑談の大半はEvidenceを含まない）ため、
 * LLMには「該当なしなら空配列」を明示的に指示する。
 */
public final class EvidenceExtractor {

    private static final Logger logger = Logger.getLogger(EvidenceExtractor.class.getName());

    private static final String SYSTEM_PROMPT = """
        あなたはユーザーの発話から、その人となり（性格・価値観・習慣・行動・人間関係・目標・健康状態・感情）
        に関する恒久的または準恒久的な手がかり（Evidence）を抽出するアシスタントです。

        出力は必ず以下のJSON配列形式のみとし、説明文やMarkdownのコードフェンスは一切含めないこと。
        該当する手がかりが無い場合は空配列 [] を返すこと。

        [
          {
            "category": "EMOTION|PERSONALITY|VALUE|HABIT|BEHAVIOR|RELATIONSHIP|GOAL|HEALTH",
            "topic": "短いトピック名（例: 朝の散歩、母親との関係）",
            "content": "抽出した内容を一文で",
            "confidence": 0.0から1.0の数値,
            "polarity": "POSITIVE|NEGATIVE|NEUTRAL"
          }
        ]

        雑談・一時的な話題・事実性の低い発言はEvidenceにしないこと。
        """;

    private final LlmClient llmClient;
    private final MemoryConsolidator consolidator;

    public EvidenceExtractor(LlmClient llmClient, MemoryConsolidator consolidator) {
        this.llmClient = llmClient;
        this.consolidator = consolidator;
    }

    /**
     * ユーザー発話からEvidenceを抽出し、consolidateまで行う。
     * LLM呼び出し・パース失敗時は何もしない（会話継続を優先し、例外は投げない）。
     */
    public void extractAndConsolidate(long userId, String sourceEventId, String userText) {
        if (userText == null || userText.isBlank()) return;

        String raw = llmClient.call(SYSTEM_PROMPT, userText, 800, 0.2);
        if (raw == null || raw.isBlank()) {
            logger.fine("Evidence抽出: LLM応答が空でした (userId=" + userId + ")");
            return;
        }

        List<Evidence> extracted = parse(raw, userId, sourceEventId);
        for (Evidence e : extracted) {
            try {
                consolidator.consolidate(userId, e);
            } catch (RuntimeException ex) {
                logger.warning("Evidence統合に失敗しました (userId=" + userId + ", topic=" + e.topic + "): " + ex.getMessage());
            }
        }
    }

    private List<Evidence> parse(String raw, long userId, String sourceEventId) {
        List<Evidence> results = new java.util.ArrayList<>();
        String cleaned = stripCodeFence(raw);

        JsonElement root;
        try {
            root = JsonParser.parseString(cleaned);
        } catch (RuntimeException e) {
            logger.warning("Evidence抽出JSONの解析に失敗しました (userId=" + userId + "): " + e.getMessage());
            return results;
        }
        if (!root.isJsonArray()) {
            logger.warning("Evidence抽出結果がJSON配列ではありません (userId=" + userId + ")");
            return results;
        }

        JsonArray array = root.getAsJsonArray();
        for (JsonElement el : array) {
            try {
                Evidence e = fromJson(el.getAsJsonObject(), userId, sourceEventId);
                if (e != null) results.add(e);
            } catch (RuntimeException ex) {
                logger.warning("Evidence1件のパースに失敗しました (userId=" + userId + "): " + ex.getMessage());
            }
        }
        return results;
    }

    private Evidence fromJson(JsonObject obj, long userId, String sourceEventId) {
        String categoryStr = getString(obj, "category");
        String topic = getString(obj, "topic");
        String content = getString(obj, "content");
        if (categoryStr == null || topic == null || content == null) return null;

        EvidenceCategory category;
        Polarity polarity;
        try {
            category = EvidenceCategory.valueOf(categoryStr.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            logger.warning("未知のcategoryを無視します: " + categoryStr);
            return null;
        }
        String polarityStr = getString(obj, "polarity");
        try {
            polarity = polarityStr != null ? Polarity.valueOf(polarityStr.trim().toUpperCase()) : Polarity.NEUTRAL;
        } catch (IllegalArgumentException ex) {
            polarity = Polarity.NEUTRAL;
        }

        double confidence = 0.6;
        if (obj.has("confidence") && !obj.get("confidence").isJsonNull()) {
            try {
                confidence = obj.get("confidence").getAsDouble();
            } catch (RuntimeException ignored) {
                // デフォルト値を維持
            }
        }
        confidence = Math.max(0.0, Math.min(1.0, confidence));

        Evidence e = new Evidence();
        e.userId = userId;
        e.category = category;
        e.topic = topic.strip();
        e.content = content.strip();
        e.confidence = confidence;
        e.polarity = polarity;
        if (sourceEventId != null) e.sourceEventIds.add(sourceEventId);
        return e;
    }

    private String getString(JsonObject obj, String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) return null;
        return obj.get(key).getAsString();
    }

    /** LLMが```json ... ```で包んで返してくることがあるための保険。 */
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
