package tsumugi.conversation;

import tsumugi.core.model.TsumugiModel.EpisodicEvent;
import tsumugi.core.model.TsumugiModel.Evidence;
import tsumugi.core.model.TsumugiModel.Speaker;
import tsumugi.core.model.TsumugiModel.UserModel;
import tsumugi.llm.LlmClient;
import tsumugi.memory.retrieval.MemoryRetriever;
import tsumugi.memory.retrieval.RetrievalResult;
import tsumugi.memory.store.EpisodicEventRepository;
import tsumugi.memory.store.UserModelRepository;

import java.util.List;
import java.util.logging.Logger;

/**
 * 会話履歴・UserModel・関連Evidenceを文脈として組み立て、LLMに渡して応答を生成する。
 * DiscordAdapter等の入出力層からは完全に独立している。
 */
public final class ConversationEngine {

    private static final Logger logger = Logger.getLogger(ConversationEngine.class.getName());

    private static final int HISTORY_LIMIT = 10;
    private static final int RETRIEVAL_TOP_K = 5;
    private static final int MAX_TOKENS = 800;
    private static final double TEMPERATURE = 0.8;

    private static final String BASE_PERSONA_PROMPT = """
        あなたは「紬希（つむぎ）」という名前のAIアシスタントです。
        ユーザーとの継続的な関係を大切にし、これまでの記憶（Evidence）と会話履歴を踏まえて、
        自然で温かみのある日本語で応答してください。
        記憶を機械的に読み上げるのではなく、会話の流れに自然に織り込むこと。
        """;

    private final LlmClient llmClient;
    private final MemoryRetriever memoryRetriever;
    private final EpisodicEventRepository episodicEventRepository;
    private final UserModelRepository userModelRepository;

    public ConversationEngine(LlmClient llmClient,
                               MemoryRetriever memoryRetriever,
                               EpisodicEventRepository episodicEventRepository,
                               UserModelRepository userModelRepository) {
        this.llmClient = llmClient;
        this.memoryRetriever = memoryRetriever;
        this.episodicEventRepository = episodicEventRepository;
        this.userModelRepository = userModelRepository;
    }

    /**
     * ユーザーの発話に対する応答を生成する。
     * 失敗時はnullを返す（呼び出し側でフォールバック文言に置き換える想定）。
     */
    public String generateReply(long userId, String userText) {
        try {
            UserModel model = userModelRepository.load(userId);
            List<EpisodicEvent> history = episodicEventRepository.loadRecent(userId, HISTORY_LIMIT);
            List<RetrievalResult> relevant = memoryRetriever.retrieve(userId, userText, RETRIEVAL_TOP_K);

            String systemPrompt = buildSystemPrompt(model, relevant);
            String userPrompt = buildUserPrompt(history, userText);

            return llmClient.call(systemPrompt, userPrompt, MAX_TOKENS, TEMPERATURE);
        } catch (RuntimeException e) {
            logger.warning("応答生成に失敗しました (userId=" + userId + "): " + e.getMessage());
            return null;
        }
    }

    private String buildSystemPrompt(UserModel model, List<RetrievalResult> relevant) {
        StringBuilder sb = new StringBuilder(BASE_PERSONA_PROMPT);

        if (model.recentFocus != null && !model.recentFocus.isBlank()) {
            sb.append("\n最近のユーザーの関心事: ").append(model.recentFocus);
        }

        if (!relevant.isEmpty()) {
            sb.append("\n\n以下はユーザーについて記憶している関連情報です（参考程度に、自然に使うこと）:\n");
            for (RetrievalResult r : relevant) {
                Evidence e = r.evidence();
                sb.append("- [").append(e.category).append("] ").append(e.topic)
                        .append(": ").append(e.content).append("\n");
            }
        }

        if (!model.openQuestions.isEmpty()) {
            sb.append("\nまだ聞けていないこと（機会があれば自然に尋ねてよい。無理に聞かなくてよい）:\n");
            for (String q : model.openQuestions) {
                sb.append("- ").append(q).append("\n");
            }
        }

        return sb.toString();
    }

    private String buildUserPrompt(List<EpisodicEvent> history, String latestUserText) {
        StringBuilder sb = new StringBuilder();
        if (!history.isEmpty()) {
            sb.append("これまでの会話:\n");
            for (EpisodicEvent event : history) {
                String speakerLabel = event.speaker == Speaker.USER ? "ユーザー" : "紬希";
                sb.append(speakerLabel).append(": ").append(event.rawText).append("\n");
            }
            sb.append("\n");
        }
        sb.append("ユーザーの最新の発話:\n").append(latestUserText);
        return sb.toString();
    }
}
