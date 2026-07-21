package tsumugi.memory.store;

import tsumugi.core.model.TsumugiModel.Evidence;
import tsumugi.core.model.TsumugiModel.EvidenceCategory;

import java.util.List;

/**
 * Evidenceの永続化インタフェース。実装をSQLite以外（将来的にPostgres+pgvector等）
 * に差し替え可能にするため、上位層（MemoryConsolidator/MemoryRetriever）は
 * この抽象にのみ依存する。
 *
 * ユーザーごとに物理DBファイルを分離する構成になったため、
 * isVecAvailable/deleteByIdもuserId単位で判定・操作する形に変更している
 * （どのユーザーのDBファイルを開くか特定するため）。
 *
 * 利用規約第10条（閲覧権）・第11条（修正権）・第12条（削除権）・
 * 第13条（データエクスポート権）に対応するメソッドを含む。
 */
public interface EvidenceRepository {

    void save(Evidence evidence);

    List<Evidence> loadActive(long userId, EvidenceCategory category);

    List<Evidence> loadActive(long userId);

    List<Evidence> searchByVector(long userId, float[] queryEmbedding, int topK);

    boolean isVecAvailable(long userId);

    /** ステータス問わず、指定ユーザーの全Evidenceを取得する（閲覧権・エクスポート権対応）。 */
    List<Evidence> loadAll(long userId);

    /** 単一Evidenceをidで削除する（部分的な削除権対応）。 */
    void deleteById(long userId, String evidenceId);

    /** 指定ユーザーの全Evidenceを削除する（全削除権対応）。削除件数を返す。 */
    int deleteAll(long userId);
}
