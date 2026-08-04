package tsumugi.conversation;

import tsumugi.core.model.TsumugiModel.EpisodicEvent;
import tsumugi.core.model.TsumugiModel.Evidence;
import tsumugi.core.model.TsumugiModel.RelationshipEntry;
import tsumugi.core.model.TsumugiModel.Speaker;
import tsumugi.core.model.TsumugiModel.UserModel;
import tsumugi.llm.LlmClient;
import tsumugi.memory.retrieval.MemoryRetriever;
import tsumugi.memory.retrieval.RetrievalResult;
import tsumugi.memory.store.EpisodicEventRepository;
import tsumugi.memory.store.UserModelRepository;

import java.util.List;

/**
 * ユーザー発言 -> (記憶検索 + UserModel + 直近履歴からプロンプト構築) -> LLM呼び出し -> 応答文字列
 * までを担う。Discordアダプタ等の入出力チャネルには依存しない。
 *
 * 【変更点】応答生成の最大トークン数（旧: 定数 MAX_TOKENS = 800 のハードコード）を、
 * コンストラクタ引数として外部（AppConfig / .envの LLM_MAX_TOKENS_CHAT）から注入する形に変更した。
 * モデルを差し替えた際にコードを触らず .env だけで調整できるようにするための変更。
 */
public final class ConversationEngine {

    private static final int RETRIEVAL_TOP_K = 5;
    private static final int RECENT_HISTORY_LIMIT = 12;
    private static final double TEMPERATURE = 0.8;

    private static final String BASE_PERSONA = """
            あなたは「紬希」という名前のパーソナルAIコンパニオンです。
            ユーザーとの継続的な関係を大切にし、これまでの記憶（Evidence）を踏まえて、
            親しみやすく、かつ誠実な口調で会話してください。
            記憶にない情報を断定的に話さないでください。分からないことは素直に尋ねてください。
            """;

    private final LlmClient llmClient;
    private final MemoryRetriever retriever;
    private final UserModelRepository userModelRepository;
    private final EpisodicEventRepository episodicEventRepository;

    /** 応答生成に使う最大トークン数。AppConfig.llmMaxTokensChat（.envの LLM_MAX_TOKENS_CHAT）から注入される。 */
    private final int maxTokens;

    public ConversationEngine(LlmClient llmClient,
                               MemoryRetriever retriever,
                               UserModelRepository userModelRepository,
                               EpisodicEventRepository episodicEventRepository,
                               int maxTokens) {
        this.llmClient = llmClient;
        this.retriever = retriever;
        this.userModelRepository = userModelRepository;
        this.episodicEventRepository = episodicEventRepository;
        this.maxTokens = maxTokens;
    }

    public String generateReply(long userId, String userText) {
        UserModel model = userModelRepository.load(userId);
        List<RetrievalResult> memories = retriever.retrieve(userId, userText, RETRIEVAL_TOP_K);
        List<EpisodicEvent> recentHistory = episodicEventRepository.loadRecent(userId, RECENT_HISTORY_LIMIT);

        String systemPrompt = buildSystemPrompt(model, memories, recentHistory);
        return llmClient.call(systemPrompt, userText, maxTokens, TEMPERATURE);
    }

    private String buildSystemPrompt(UserModel model, List<RetrievalResult> memories, List<EpisodicEvent> recentHistory) {
        StringBuilder sb = new StringBuilder(BASE_PERSONA);

        sb.append("\n## ユーザーに関する既知の情報（Evidence）\n");
        if (memories.isEmpty()) {
            sb.append("（現時点で関連する記憶はありません）\n");
        } else {
            for (RetrievalResult r : memories) {
                Evidence e = r.evidence();
                sb.append("- [").append(e.category).append("] ")
                        .append(e.topic).append(": ").append(e.content)
                        .append(" (確信度=").append(String.format("%.2f", e.confidence)).append(")\n");
            }
        }

        if (model.currentEmotion != null) {
            sb.append("\n## 直近の感情状態\n")
                    .append("valence=").append(model.currentEmotion.valence)
                    .append(", arousal=").append(model.currentEmotion.arousal);
            if (model.currentEmotion.note != null && !model.currentEmotion.note.isBlank()) {
                sb.append(", note=").append(model.currentEmotion.note);
            }
            sb.append("\n");
        }

        if (!model.relationships.isEmpty()) {
            sb.append("\n## 関係のある人物\n");
            for (RelationshipEntry r : model.relationships) {
                sb.append("- ").append(r.label).append(": ").append(r.note).append("\n");
            }
        }

        if (!model.openQuestions.isEmpty()) {
            sb.append("\n## まだ聞けていないこと（機会があれば自然に聞いてみる）\n");
            for (String q : model.openQuestions) {
                sb.append("- ").append(q).append("\n");
            }
        }

        if (!recentHistory.isEmpty()) {
            sb.append("\n## 直近の会話履歴\n");
            for (EpisodicEvent ev : recentHistory) {
                String who = ev.speaker == Speaker.USER ? "ユーザー" : "紬希";
                sb.append(who).append(": ").append(ev.rawText).append("\n");
            }
        }

        sb.append("\n上記を踏まえて、次のユーザー発言に対して紬希として自然に返信してください。");
        return sb.toString();
    }
}
