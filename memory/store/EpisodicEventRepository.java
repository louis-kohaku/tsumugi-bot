package tsumugi.memory.store;

import tsumugi.core.model.TsumugiModel.EpisodicEvent;

import java.time.LocalDate;
import java.util.List;

/** EpisodicEvent（会話の生ログ）の永続化インタフェース。 */
public interface EpisodicEventRepository {

    void save(EpisodicEvent event);

    /** userIdの直近イベントをoccurredAt降順で最大limit件取得する。 */
    List<EpisodicEvent> loadRecent(long userId, int limit);

    List<EpisodicEvent> loadByLogicalDate(long userId, LocalDate logicalDate);
}
