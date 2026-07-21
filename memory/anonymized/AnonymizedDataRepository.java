package tsumugi.memory.anonymized;

import tsumugi.core.model.TsumugiModel.Evidence;

import java.util.List;

/**
 * 匿名化済み研究データの永続化インタフェース。
 * EvidenceRepositoryとは完全に別系統で、個人特定情報を一切扱わない。
 */
public interface AnonymizedDataRepository {

    /**
     * EvidenceのリストをAnonymizedEvidenceへ変換し、匿名データとして保存する。
     * userId・sourceEventIds・元のid等、個人を特定しうる情報は保存前に破棄される。
     */
    void saveAnonymized(List<Evidence> evidences);
}
