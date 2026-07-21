package tsumugi.memory.store;

import tsumugi.core.model.TsumugiModel.EpisodicEvent;

import java.util.List;

/**
 * EpisodicEvent（発話ログ）の永続化インタフェース。
 * MemoryConsolidator/MemoryRetrieverと同様、上位層はこの抽象にのみ依存する。
 *
 * 利用規約第10条（閲覧権）・第12条（削除権）・第13条（データエクスポート権）に
 * 対応するため、ユーザー単位の全件取得・全件削除を提供する。
 */
public interface EpisodicEventRepository {

    void save(EpisodicEvent event);

    /** 直近のイベントを新しい順に指定件数取得する（会話履歴の文脈構築用）。 */
    List<EpisodicEvent> loadRecent(long userId, int limit);

    /** 指定ユーザーの全イベントを時系列昇順で取得する（閲覧権・エクスポート権対応）。 */
    List<EpisodicEvent> loadAll(long userId);

    /** 指定ユーザーの全イベントを削除する（削除権対応）。削除件数を返す。 */
    int deleteAll(long userId);
}
