package tsumugi.memory.anonymized;

import tsumugi.core.model.TsumugiModel.EvidenceCategory;
import tsumugi.core.model.TsumugiModel.Polarity;

import java.time.Instant;
import java.util.UUID;

/**
 * 退会時「匿名で保存」を選んだ場合、または再入室時の引継ぎ確認で「いいえ」
 * （引き継がない）を選んだ場合に、研究目的で残す匿名化済みデータ。
 *
 * 個人を特定できる情報（userId, sourceEventIds, 元のEvidence id, 発話生ログ等）は
 * 一切含めない。感情・性格・習慣などの傾向データ（category/topic/content/confidence/polarity）
 * のみを、元データとは無関係な新規idで保存する。
 * 元のEvidence/EpisodicEventとの突合が一切できないことが本クラスの目的。
 */
public final class AnonymizedEvidence {
    public String id;
    public EvidenceCategory category;
    public String topic;
    public String content;
    public double confidence;
    public Polarity polarity;
    public Instant anonymizedAt;

    public AnonymizedEvidence() {
        this.id = UUID.randomUUID().toString();
        this.anonymizedAt = Instant.now();
    }
}
