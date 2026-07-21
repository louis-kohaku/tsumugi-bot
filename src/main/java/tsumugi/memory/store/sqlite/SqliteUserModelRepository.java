package tsumugi.memory.store.sqlite;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import tsumugi.core.model.TsumugiModel.UserModel;
import tsumugi.memory.store.UserModelRepository;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.logging.Logger;

/**
 * UserModelをJSON1本化してユーザーごとに分離されたSQLiteファイル
 * （{userDbDir}/{userId}/tsumugi.db）のuser_modelテーブルに保存する。
 */
public final class SqliteUserModelRepository implements UserModelRepository {

    private static final Logger logger = Logger.getLogger(SqliteUserModelRepository.class.getName());

    // java.time系はGsonのリフレクションベースシリアライズがJava21のモジュール制限で
    // 失敗するため、toString()/parse()ベースのTypeAdapterを明示的に登録する。
    private static final TypeAdapter<Instant> INSTANT_ADAPTER = new TypeAdapter<>() {
        @Override
        public void write(JsonWriter out, Instant value) throws IOException {
            if (value == null) out.nullValue(); else out.value(value.toString());
        }

        @Override
        public Instant read(JsonReader in) throws IOException {
            if (in.peek() == JsonToken.NULL) { in.nextNull(); return null; }
            return Instant.parse(in.nextString());
        }
    };

    private static final TypeAdapter<LocalDate> LOCAL_DATE_ADAPTER = new TypeAdapter<>() {
        @Override
        public void write(JsonWriter out, LocalDate value) throws IOException {
            if (value == null) out.nullValue(); else out.value(value.toString());
        }

        @Override
        public LocalDate read(JsonReader in) throws IOException {
            if (in.peek() == JsonToken.NULL) { in.nextNull(); return null; }
            return LocalDate.parse(in.nextString());
        }
    };

    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(Instant.class, INSTANT_ADAPTER)
            .registerTypeAdapter(LocalDate.class, LOCAL_DATE_ADAPTER)
            .create();

    private final UserConnectionFactoryRegistry registry;

    public SqliteUserModelRepository(UserConnectionFactoryRegistry registry) {
        this.registry = registry;
    }

    @Override
    public UserModel load(long userId) {
        String sql = "SELECT payload FROM user_model WHERE user_id=?";
        try (Connection conn = registry.forUser(userId).open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    UserModel model = GSON.fromJson(rs.getString("payload"), UserModel.class);
                    return model != null ? model : createInitialModel(userId);
                }
                return createInitialModel(userId);
            }
        } catch (SQLException e) {
            logger.warning("UserModelの読み込みに失敗しました (userId=" + userId + "): " + e.getMessage());
            return createInitialModel(userId);
        }
    }

    @Override
    public void save(long userId, UserModel model) {
        String sql = """
            INSERT INTO user_model (user_id, payload, updated_at)
            VALUES (?, ?, ?)
            ON CONFLICT(user_id) DO UPDATE SET payload=excluded.payload, updated_at=excluded.updated_at
            """;
        try (Connection conn = registry.forUser(userId).open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setString(2, GSON.toJson(model));
            ps.setString(3, Instant.now().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.warning("UserModelの保存に失敗しました (userId=" + userId + "): " + e.getMessage());
        }
    }

    @Override
    public void delete(long userId) {
        String sql = "DELETE FROM user_model WHERE user_id=?";
        try (Connection conn = registry.forUser(userId).open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.warning("UserModelの削除に失敗しました (userId=" + userId + "): " + e.getMessage());
        }
    }

    private UserModel createInitialModel(long userId) {
        UserModel model = new UserModel(userId);
        model.openQuestions.addAll(List.of(
                "最近ハマっていること・好きなこと",
                "最近悩んでいること",
                "最近の睡眠や体調の傾向"
        ));
        return model;
    }
}
