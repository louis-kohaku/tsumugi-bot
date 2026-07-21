package tsumugi.memory.rights;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import tsumugi.core.model.TsumugiModel.EpisodicEvent;
import tsumugi.core.model.TsumugiModel.Evidence;
import tsumugi.core.model.TsumugiModel.UserModel;
import tsumugi.memory.store.EpisodicEventRepository;
import tsumugi.memory.store.EvidenceRepository;
import tsumugi.memory.store.UserModelRepository;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.logging.Logger;

/**
 * 利用規約・AI利用者権利章典に定める以下の権利をアプリケーション層でまとめて扱う窓口。
 *
 *  - 第10条 / 第2条（閲覧する権利）: exportUserData()
 *  - 第12条 / 第4条（忘れられる権利）: forgetUser()
 *  - 第13条 / 第5条（データを持ち出す権利）: exportUserData()の出力をそのまま機械可読JSONとして提供可能
 *
 * 第11条（訂正する権利）については、EvidenceRepository.deleteById + 再抽出/手動再登録、
 * または将来的なEvidence個別更新APIで対応する想定であり、本クラスはその土台として
 * 削除・エクスポートの一括操作のみを担う。
 */
public final class DataSubjectRightsService {

    private static final Logger logger = Logger.getLogger(DataSubjectRightsService.class.getName());

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

    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(Instant.class, INSTANT_ADAPTER)
            .setPrettyPrinting()
            .create();

    private final EpisodicEventRepository episodicEventRepository;
    private final EvidenceRepository evidenceRepository;
    private final UserModelRepository userModelRepository;

    public DataSubjectRightsService(EpisodicEventRepository episodicEventRepository,
                                     EvidenceRepository evidenceRepository,
                                     UserModelRepository userModelRepository) {
        this.episodicEventRepository = episodicEventRepository;
        this.evidenceRepository = evidenceRepository;
        this.userModelRepository = userModelRepository;
    }

    /**
     * 利用規約第10条（閲覧権）・第13条（データエクスポート権）対応。
     * 指定ユーザーの会話履歴・Evidence（ステータス問わず全件）・UserModelを
     * 人間が読める/機械可読なJSON文字列として返す。
     */
    public String exportUserData(long userId) {
        ExportPayload payload = new ExportPayload();
        payload.userId = userId;
        payload.exportedAt = Instant.now();
        payload.episodicEvents = episodicEventRepository.loadAll(userId);
        payload.evidences = evidenceRepository.loadAll(userId);
        payload.userModel = userModelRepository.load(userId);
        return GSON.toJson(payload);
    }

    /**
     * 利用規約第12条（削除権）・第4条（忘れられる権利）対応。
     * 会話履歴・Evidence（sqlite-vec側の埋め込みを含む）・UserModelを全て削除する。
     * ※法令上の保存義務がある情報や匿名化済み研究データ（第15条・第32条2項）は
     *   本メソッドの対象外であり、別途研究データ側で管理する。
     *
     * @return 削除した件数の内訳
     */
    public ForgetResult forgetUser(long userId) {
        int deletedEvents = episodicEventRepository.deleteAll(userId);
        int deletedEvidence = evidenceRepository.deleteAll(userId);
        userModelRepository.delete(userId);

        logger.info("ユーザーデータを削除しました (userId=" + userId
                + ", episodicEvents=" + deletedEvents
                + ", evidence=" + deletedEvidence + ")");

        return new ForgetResult(deletedEvents, deletedEvidence);
    }

    public record ForgetResult(int deletedEpisodicEvents, int deletedEvidences) {}

    private static final class ExportPayload {
        long userId;
        Instant exportedAt;
        List<EpisodicEvent> episodicEvents;
        List<Evidence> evidences;
        UserModel userModel;
    }
}
