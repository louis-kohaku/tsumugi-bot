package tsumugi.memory.extract;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import tsumugi.core.model.TsumugiModel.Evidence;
import tsumugi.core.model.TsumugiModel.EvidenceCategory;
import tsumugi.core.model.TsumugiModel.Polarity;
import tsumugi.llm.LlmClient;
import tsumugi.memory.consolidate.MemoryConsolidator;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * ユーザー発言（1メッセージ単位）からEvidence候補をLLMに抽出させ、
 * MemoryConsolidatorへ渡して永続化・UserModel反映まで行う。
 * 抽出は失敗しても会話自体は止めたくないので、例外は握りつぶしてログのみ出す。
 */
public final class EvidenceExtractor {

    private static final Logger logger = Logger.getLogger(EvidenceExtractor.class.getName());
    private static final Gson GSON = new Gson();

    private static final String SYSTEM_PROMPT = """
            あなたはユーザーの発言から、ユーザー本人に関する事実（evidence）を抽出するアシスタントです。
            以下のcategoryのみを使用してください: EMOTION, PERSONALITY, VALUE, HABIT, BEHAVIOR, RELATIONSHIP, GOAL, HEALTH
            polarityは POSITIVE, NEGATIVE, NEUTRAL のいずれかです。
            confidenceは0.0〜1.0で、発言からどれだけ確信を持ってその事実が言えるかを表します。
            雑談的な相槌や、事実性の薄い発言からは何も抽出しないでください。

            出力は次の形式のJSON配列のみとしてください。説明文やコードブロック記法（```）は一切含めないでください。
            該当する事実がなければ空配列 [] を返してください。

            [
              {"category": "HABIT", "topic": "短いトピック名", "content": "事実の内容（一文）", "confidence": 0.8, "polarity": "POSITIVE"}
            ]
            """;

    private final LlmClient llmClient;
    private final MemoryConsolidator consolidator;

    public EvidenceExtractor(LlmClient llmClient, MemoryConsolidator consolidator) {
        this.llmClient = llmClient;
        this.consolidator = consolidator;
    }

    /** userTextからEvidenceを抽出し、consolidatorに反映する。反映されたEvidence一覧を返す。 */
    public List<Evidence> extractAndConsolidate(long userId, String sourceEventId, String userText) {
        List<Evidence> applied = new ArrayList<>();
        if (userText == null || userText.isBlank()) return applied;

        String raw = llmClient.call(SYSTEM_PROMPT, userText, 512, 0.2);
        if (raw == null || raw.isBlank()) {
            logger.warning("Evidence抽出のLLM応答が空でした (userId=" + userId + ")");
            return applied;
        }

        List<ExtractedEvidenceDto> dtos;
        try {
            String cleaned = stripCodeFence(raw);
            ExtractedEvidenceDto[] arr = GSON.fromJson(cleaned, ExtractedEvidenceDto[].class);
            dtos = arr == null ? List.of() : List.of(arr);
        } catch (JsonSyntaxException e) {
            logger.warning("Evidence抽出結果のJSON解析に失敗しました (userId=" + userId + "): " + e.getMessage());
            return applied;
        }

        for (ExtractedEvidenceDto dto : dtos) {
            Evidence evidence = toEvidence(dto, sourceEventId);
            if (evidence == null) continue;
            try {
                applied.add(consolidator.consolidate(userId, evidence));
            } catch (RuntimeException e) {
                logger.warning("Evidenceの統合に失敗しました (userId=" + userId + "): " + e.getMessage());
            }
        }
        return applied;
    }

    private Evidence toEvidence(ExtractedEvidenceDto dto, String sourceEventId) {
        if (dto == null || dto.category == null || dto.content == null || dto.content.isBlank()) return null;
        try {
            Evidence e = new Evidence();
            e.category = EvidenceCategory.valueOf(dto.category.trim().toUpperCase());
            e.topic = dto.topic != null ? dto.topic.trim() : dto.content.trim();
            e.content = dto.content.trim();
            e.confidence = Math.max(0.0, Math.min(1.0, dto.confidence));
            e.polarity = dto.polarity != null
                    ? Polarity.valueOf(dto.polarity.trim().toUpperCase())
                    : Polarity.NEUTRAL;
            if (sourceEventId != null) e.sourceEventIds.add(sourceEventId);
            return e;
        } catch (IllegalArgumentException ex) {
            logger.warning("不正なcategory/polarityのためEvidence抽出をスキップしました: " + dto.category + "/" + dto.polarity);
            return null;
        }
    }

    private String stripCodeFence(String raw) {
        String trimmed = raw.trim();
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceFirst("^```[a-zA-Z]*\\s*", "");
            if (trimmed.endsWith("```")) {
                trimmed = trimmed.substring(0, trimmed.length() - 3);
            }
        }
        return trimmed.trim();
    }

    private static final class ExtractedEvidenceDto {
        String category;
        String topic;
        String content;
        double confidence;
        String polarity;
    }
}
