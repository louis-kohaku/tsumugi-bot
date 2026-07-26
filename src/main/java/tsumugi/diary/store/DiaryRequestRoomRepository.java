package tsumugi.diary.store;

/**
 * ユーザーごとの「要望部屋」チャンネルIDを永続化するインタフェース。
 *
 * DiaryChannelService.ensureRequestRoom() が以前はチャンネル名の文字列一致で
 * 既存チャンネルを探していたが、Discordがチャンネル名を自動正規化（例: 英字の
 * 小文字化）するため、表示名にアルファベット大文字を含むユーザー等で一致判定が
 * 常に失敗し、呼び出すたびに新規チャンネルが量産されるバグがあった。
 *
 * この修正では「作成したチャンネルのIDをここに保存し、次回以降はIDで存在確認する」
 * 方式に変更する。名前の正規化ルールに依存しないため、上記の問題が起きない。
 */
public interface DiaryRequestRoomRepository {

    /** 指定ユーザーの要望部屋チャンネルIDを返す。未登録ならnull。 */
    Long loadChannelId(long userId);

    /** 指定ユーザーの要望部屋チャンネルIDを保存（新規登録・更新の両方に使う）。 */
    void save(long userId, long channelId);

    /** 指定ユーザーの要望部屋チャンネルIDの登録を削除する（退会・データ削除権対応等で使う想定）。 */
    void delete(long userId);
}
