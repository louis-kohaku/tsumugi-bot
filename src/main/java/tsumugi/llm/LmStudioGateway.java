package tsumugi.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import tsumugi.memory.embedding.EmbeddingClient;

import java.io.IOException;
import java.util.logging.Logger;

/**
 * LM Studio（ローカルLLM、OpenAI互換API）への薄いHTTPクライアント。
 *
 * モデルのロード/アンロードは自前で管理せず、LM Studio自体のJITロード＋
 * アイドルTTL機構に委譲する。リクエストボディに"ttl"（秒）を含めるだけで、
 * そのモデルが指定秒数アイドルになった際に自動アンロードされる。
 *
 * 運用前提：
 * ・LM Studio側の Auto-Evict は OFF にしておくこと
 *   （ONだと会話モデルとembeddingモデルを交互に呼ぶたびに
 *     ロード/アンロードを繰り返してしまうため）
 * ・上記により、会話用モデルとembedding用モデルが個別のTTLタイマーで
 *   同時に常駐できる
 */
public final class LmStudioGateway implements LlmClient, EmbeddingClient {

    private static final Logger logger = Logger.getLogger(LmStudioGateway.class.getName());

    /** アイドル状態が何秒続いたらLM Studio側がモデルをアンロードするか */
    private static final int IDLE_TTL_SECONDS = 600; // 10分

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final String baseUrl;
    private final String chatModelKey;
    private final String embeddingModelKey;
    private final OkHttpClient http;

    public LmStudioGateway(String baseUrl, String chatModelKey, String embeddingModelKey, OkHttpClient http) {
        this.baseUrl = baseUrl;
        this.chatModelKey = chatModelKey;
        this.embeddingModelKey = embeddingModelKey;
        this.http = http;
    }

    // ═══════════════════════════════════════
    //  LlmClient
    // ═══════════════════════════════════════

    @Override
    public String call(String systemPrompt, String userPrompt, int maxTokens, double temperature) {
        JsonObject body = new JsonObject();
        body.addProperty("model", chatModelKey);
        body.addProperty("ttl", IDLE_TTL_SECONDS);
        body.addProperty("max_tokens", maxTokens);
        body.addProperty("temperature", temperature);

        JsonArray messages = new JsonArray();
        messages.add(chatMessage("system", systemPrompt));
        messages.add(chatMessage("user", userPrompt));
        body.add("messages", messages);

        JsonObject response = post("/api/v0/chat/completions", body);
        if (response == null) return null;

        try {
            return response
                    .getAsJsonArray("choices").get(0).getAsJsonObject()
                    .getAsJsonObject("message")
                    .get("content").getAsString();
        } catch (RuntimeException e) {
            logger.warning("LM Studio応答の解析に失敗しました: " + e.getMessage());
            return null;
        }
    }

    // ═══════════════════════════════════════
    //  EmbeddingClient
    // ═══════════════════════════════════════

    @Override
    public float[] embed(String text) {
        if (embeddingModelKey == null || embeddingModelKey.isBlank()) {
            logger.warning("embeddingモデルが未設定のため、embedを呼び出せません。");
            return null;
        }

        JsonObject body = new JsonObject();
        body.addProperty("model", embeddingModelKey);
        body.addProperty("ttl", IDLE_TTL_SECONDS);
        body.addProperty("input", text);

        JsonObject response = post("/api/v0/embeddings", body);
        if (response == null) return null;

        try {
            JsonArray vec = response
                    .getAsJsonArray("data").get(0).getAsJsonObject()
                    .getAsJsonArray("embedding");
            float[] result = new float[vec.size()];
            for (int i = 0; i < vec.size(); i++) {
                result[i] = vec.get(i).getAsFloat();
            }
            return result;
        } catch (RuntimeException e) {
            logger.warning("embedding応答の解析に失敗しました: " + e.getMessage());
            return null;
        }
    }

    // ═══════════════════════════════════════
    //  内部ヘルパー
    // ═══════════════════════════════════════

    private JsonObject chatMessage(String role, String content) {
        JsonObject m = new JsonObject();
        m.addProperty("role", role);
        m.addProperty("content", content);
        return m;
    }

    private JsonObject post(String path, JsonObject body) {
        RequestBody requestBody = RequestBody.create(body.toString(), JSON);

        Request request = new Request.Builder()
                .url(baseUrl + path)
                .post(requestBody)
                .build();

        try (Response response = http.newCall(request).execute()) {

            String raw = response.body() != null
                    ? response.body().string()
                    : "";

            if (!response.isSuccessful()) {
                logger.warning("""
    LM Studio Error
    HTTP: %d
    PATH: %s

    REQUEST:
    %s

    RESPONSE:
    %s
    """.formatted(
                        response.code(),
                        path,
                        body,
                        raw
                ));
                return null;
            }

            return JsonParser.parseString(raw).getAsJsonObject();
        } catch (IOException e) {
            logger.warning("LM Studioへの接続に失敗しました: " + e.getMessage());
            return null;
        } catch (RuntimeException e) {
            logger.warning("JSON解析失敗: " + e.getMessage());
            return null;
        }
    }
}
