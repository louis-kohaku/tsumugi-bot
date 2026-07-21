package tsumugi.memory.retrieval;

import tsumugi.core.model.TsumugiModel.Evidence;
import tsumugi.memory.embedding.EmbeddingClient;
import tsumugi.memory.store.EvidenceRepository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 会話文脈に関連するEvidenceを取得する。ベクトル検索（意味的類似度）を主軸に、
 * sqlite-vecが利用できない場合はキーワード一致のみにフォールバックする。
 */
public final class MemoryRetriever {

    private static final Logger logger = Logger.getLogger(MemoryRetriever.class.getName());

    private static final int VECTOR_CANDIDATE_MULTIPLIER = 3;
    private static final double MIN_SCORE_THRESHOLD = 0.15;

    private final EvidenceRepository evidenceRepository;
    private final EmbeddingClient embeddingClient;

    public MemoryRetriever(EvidenceRepository evidenceRepository, EmbeddingClient embeddingClient) {
        this.evidenceRepository = evidenceRepository;
        this.embeddingClient = embeddingClient;
    }

    public List<RetrievalResult> retrieve(long userId, String queryText, int topK) {
        if (queryText == null || queryText.isBlank()) return new ArrayList<>();

        if (evidenceRepository.isVecAvailable(userId)) {
            float[] queryVec = embeddingClient.embed(queryText);
            if (queryVec != null) {
                return retrieveByVector(userId, queryText, queryVec, topK);
            }
            logger.warning("embedding生成に失敗したため、キーワード検索にフォールバックします。");
        }
        return retrieveByKeyword(userId, queryText, topK);
    }

    private List<RetrievalResult> retrieveByVector(long userId, String queryText, float[] queryVec, int topK) {
        List<Evidence> candidates = evidenceRepository.searchByVector(
                userId, queryVec, topK * VECTOR_CANDIDATE_MULTIPLIER);

        Set<String> queryTokens = tokenize(queryText);

        List<RetrievalResult> scored = new ArrayList<>();
        for (Evidence e : candidates) {
            double keywordScore = keywordOverlap(queryTokens, e.content);
            double combined = (0.7 + keywordScore * 0.3) * e.confidence;
            scored.add(new RetrievalResult(e, combined));
        }
        return finalize(scored, topK);
    }

    private List<RetrievalResult> retrieveByKeyword(long userId, String queryText, int topK) {
        Set<String> queryTokens = tokenize(queryText);
        List<Evidence> all = evidenceRepository.loadActive(userId);

        List<RetrievalResult> scored = new ArrayList<>();
        for (Evidence e : all) {
            double keywordScore = keywordOverlap(queryTokens, e.content);
            if (keywordScore <= 0.0) continue;
            scored.add(new RetrievalResult(e, keywordScore * e.confidence));
        }
        return finalize(scored, topK);
    }

    private List<RetrievalResult> finalize(List<RetrievalResult> scored, int topK) {
        return scored.stream()
                .filter(r -> r.score() > MIN_SCORE_THRESHOLD)
                .sorted(Comparator.comparingDouble(RetrievalResult::score).reversed())
                .limit(topK)
                .collect(Collectors.toList());
    }

    private static final Pattern TOKEN_PATTERN = Pattern.compile("[\\p{L}\\p{N}]{2,}");

    private Set<String> tokenize(String text) {
        Set<String> tokens = new java.util.HashSet<>();
        Matcher m = TOKEN_PATTERN.matcher(text);
        while (m.find()) tokens.add(m.group());
        return tokens;
    }

    private double keywordOverlap(Set<String> queryTokens, String content) {
        if (queryTokens.isEmpty() || content == null) return 0.0;
        Set<String> contentTokens = tokenize(content);
        if (contentTokens.isEmpty()) return 0.0;
        long overlap = queryTokens.stream().filter(contentTokens::contains).count();
        return (double) overlap / queryTokens.size();
    }
}
