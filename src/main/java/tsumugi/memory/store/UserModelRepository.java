package tsumugi.memory.store;

import tsumugi.core.model.TsumugiModel.UserModel;

/**
 * UserModel（現在状態）の永続化インタフェース。
 * 利用規約第12条（削除権）に対応するため削除メソッドを含む。
 */
public interface UserModelRepository {

    /** 存在しなければ新規のUserModelを生成して返す（初回接触時のデフォルト状態） */
    UserModel load(long userId);

    void save(long userId, UserModel model);

    /** 指定ユーザーのUserModelを削除する（削除権対応）。 */
    void delete(long userId);
}
