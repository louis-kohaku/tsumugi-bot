package tsumugi.memory.consolidate;

import tsumugi.core.model.TsumugiModel.Evidence;
import tsumugi.core.model.TsumugiModel.EvidenceStatus;
import tsumugi.core.model.TsumugiModel.EmotionState;
import tsumugi.core.model.TsumugiModel.RelationshipEntry;
import tsumugi.core.model.TsumugiModel.UserModel;
import tsumugi.memory.embedding.EmbeddingClient;
import tsumugi.memory.store.EvidenceRepository;
import tsumugi.memory.store.UserModelRepository;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;

/**
 * Evidenceの生成をUserModelへ反映する唯一の経路（書き込みはここに一本化する）。
 * すみれのMemoryConsolidatorを継承しつつ、同一トピック判定を
 * 文字列一致からembedding類似度ベースに強化している。
 */
public final class MemoryConsolidator {

    private static final Logger logger = Logger.getLogger(MemoryConsolidator.class.getName());

    private static final long CONTRADICTION_WINDOW_DAYS = 14;
    private static final double SAME_TOPIC_THRESHOLD = 0.85;

    private final UserModelRepository userModelRepository;
    private final EvidenceRepository evidenceRepository;
    private final EmbeddingClient embeddingClient;

    public MemoryConsolidator(UserModelRepository userModelRepository,
                               EvidenceRepository evidenceRepository,
                               EmbeddingClient embeddingClient) {
        this.userModelRepository = userModelRepository;
        this.evidenceRepository = evidenceRepository;
        this.embeddingClient = embeddingClient;
    }

    public Evidence consolidate(long userId, Evidence newEvidence) {
        newEvidence.userId = userId;
        if (newEvidence.embedding == null) {
            newEvidence.embedding = embeddingClient.embed(newEvidence.content);
        }

        List<Evidence> existingEvidences = evidenceRepository.loadActive(userId, newEvidence.category);
        Evidence existing = findSameTopic(existingEvidences, newEvidence);

        Evidence result;
        if (existing == null) {
            result = newEvidence;
            evidenceRepository.save(result);
        } else if (existing.polarity == newEvidence.polarity) {
            existing.confidence = weightedAverage(existing.confidence, newEvidence.confidence);
            existing.sourceEventIds = mergeDistinct(existing.sourceEventIds, newEvidence.sourceEventIds);
            existing.extractedAt = newEvidence.extractedAt;
            result = existing;
            evidenceRepository.save(result);
        } else {
            result = handleContradiction(existing, newEvidence);
            evidenceRepository.save(existing);
            evidenceRepository.save(result);
        }

        applyToUserModel(userId, result);
        return result;
    }

    private Evidence findSameTopic(List<Evidence> existingEvidences, Evidence newEvidence) {
        if (existingEvidences == null || existingEvidences.isEmpty()) return null;

        if (newEvidence.embedding == null) {
            return findSameTopicByStringMatch(existingEvidences, newEvidence);
        }

        Evidence best = null;
        double bestScore = SAME_TOPIC_THRESHOLD;
        for (Evidence e : existingEvidences) {
            if (e.status != EvidenceStatus.ACTIVE || e.category != newEvidence.category) continue;
            if (e.embedding == null) continue;
            double sim = cosineSimilarity(e.embedding, newEvidence.embedding);
            if (sim > bestScore) {
                bestScore = sim;
                best = e;
            }
        }
        return best;
    }

    private Evidence findSameTopicByStringMatch(List<Evidence> existingEvidences, Evidence newEvidence) {
        String normalizedNewTopic = normalizeTopic(newEvidence.topic);
        for (Evidence e : existingEvidences) {
            if (e.status == EvidenceStatus.ACTIVE
                    && e.category == newEvidence.category
                    && Objects.equals(normalizeTopic(e.topic), normalizedNewTopic)) {
                return e;
            }
        }
        return null;
    }

    private String normalizeTopic(String topic) {
        return topic == null ? "" : topic.strip();
    }

    private double cosineSimilarity(float[] a, float[] b) {
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) return 0.0;
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private double weightedAverage(double existingConfidence, double newConfidence) {
        return Math.min(1.0, (existingConfidence * 2 + newConfidence) / 3.0);
    }

    private List<String> mergeDistinct(List<String> a, List<String> b) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (a != null) merged.addAll(a);
        if (b != null) merged.addAll(b);
        return new ArrayList<>(merged);
    }

    private Evidence handleContradiction(Evidence existing, Evidence newEvidence) {
        boolean recentlyVolatile = Duration.between(existing.extractedAt, newEvidence.extractedAt)
                .toDays() <= CONTRADICTION_WINDOW_DAYS;

        existing.status = EvidenceStatus.SUPERSEDED;
        newEvidence.supersedes = existing.id;

        if (recentlyVolatile) {
            newEvidence.status = EvidenceStatus.CONTRADICTED;
            newEvidence.confidence = Math.min(newEvidence.confidence, 0.5);
            logger.info("Evidence矛盾を検知（保留扱い）: " + existing.id + " -> " + newEvidence.id);
        } else {
            newEvidence.status = EvidenceStatus.ACTIVE;
            logger.info("Evidence更新（変化として記録）: " + existing.id + " -> " + newEvidence.id);
        }
        return newEvidence;
    }

    private void applyToUserModel(long userId, Evidence evidence) {
        UserModel model = userModelRepository.load(userId);

        switch (evidence.category) {
            case EMOTION -> removeMatchedOpenQuestion(model, evidence.topic);
            case VALUE -> {
                if (evidence.status == EvidenceStatus.ACTIVE && evidence.topic != null) {
                    model.values.current.put(evidence.topic, evidence.content);
                }
                removeMatchedOpenQuestion(model, evidence.topic);
            }
            case RELATIONSHIP -> {
                upsertRelationship(model, evidence);
                removeMatchedOpenQuestion(model, evidence.topic);
            }
            case GOAL, HABIT, BEHAVIOR, PERSONALITY, HEALTH ->
                    removeMatchedOpenQuestion(model, evidence.topic);
        }

        model.lastUpdatedAt = java.time.Instant.now();
        userModelRepository.save(userId, model);
    }

    private void upsertRelationship(UserModel model, Evidence evidence) {
        for (RelationshipEntry r : model.relationships) {
            if (Objects.equals(r.label, evidence.topic)) {
                r.note = evidence.content;
                r.lastMentionedAt = evidence.extractedAt;
                if (!r.evidenceIds.contains(evidence.id)) r.evidenceIds.add(evidence.id);
                return;
            }
        }
        RelationshipEntry entry = new RelationshipEntry();
        entry.label = evidence.topic;
        entry.note = evidence.content;
        entry.lastMentionedAt = evidence.extractedAt;
        entry.evidenceIds.add(evidence.id);
        model.relationships.add(entry);
    }

    private void removeMatchedOpenQuestion(UserModel model, String topic) {
        if (topic == null) return;
        model.openQuestions.removeIf(q -> q != null && q.contains(topic));
    }

    public void applyEmotionState(long userId, EmotionState state) {
        UserModel model = userModelRepository.load(userId);
        model.currentEmotion = state;
        model.lastUpdatedAt = java.time.Instant.now();
        userModelRepository.save(userId, model);
    }
}
