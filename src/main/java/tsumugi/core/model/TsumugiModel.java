package tsumugi.core.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 紬希のドメインモデル群。Discordや特定LLMクライアントに一切依存しない。
 * すみれ（前身プロジェクト）のCompanionModelを継承しつつ、
 * 意味検索（embedding）に対応させたのが主な変更点。
 */
public final class TsumugiModel {

    private TsumugiModel() {}

    public enum ChannelType {
        NORMAL_CHAT, DIARY, MEMO, REVIEW_SESSION
    }

    public enum Speaker { USER, AI }

    public static final class EpisodicEvent {
        public String id;
        public long userId;
        public Instant occurredAt;
        public ChannelType channelType;
        public String rawText;
        public Speaker speaker;
        public LocalDate logicalDate;
        public String linkedSessionId;

        public EpisodicEvent() {}

        public EpisodicEvent(long userId, ChannelType channelType, String rawText, Speaker speaker,
                              LocalDate logicalDate, String linkedSessionId) {
            this.id = UUID.randomUUID().toString();
            this.userId = userId;
            this.occurredAt = Instant.now();
            this.channelType = channelType;
            this.rawText = rawText;
            this.speaker = speaker;
            this.logicalDate = logicalDate;
            this.linkedSessionId = linkedSessionId;
        }
    }

    public enum EvidenceCategory {
        EMOTION, PERSONALITY, VALUE, HABIT, BEHAVIOR, RELATIONSHIP, GOAL, HEALTH
    }

    public enum EvidenceStatus { ACTIVE, SUPERSEDED, CONTRADICTED }

    public enum Polarity { POSITIVE, NEGATIVE, NEUTRAL }

    public static final class Evidence {
        public String id;
        public long userId;
        public EvidenceCategory category;
        public String topic;
        public String content;
        public double confidence;
        public Polarity polarity;
        public List<String> sourceEventIds = new ArrayList<>();
        public Instant extractedAt;
        public String supersedes;
        public EvidenceStatus status;

        /** contentの埋め込みベクトル。JSON化せずDB(sqlite-vec)側のみで管理する */
        public transient float[] embedding;

        public Evidence() {
            this.id = UUID.randomUUID().toString();
            this.extractedAt = Instant.now();
            this.status = EvidenceStatus.ACTIVE;
        }
    }

    public static final class EmotionState {
        public Instant observedAt;
        public double valence;
        public double arousal;
        public List<String> dominantLabels = new ArrayList<>();
        public double confidence;
        public List<String> sourceEventIds = new ArrayList<>();
        public String note;

        public EmotionState() { this.observedAt = Instant.now(); }
    }

    public enum TrendPeriod { LAST_7_DAYS, LAST_30_DAYS }

    public static final class TrendSummary {
        public TrendPeriod period;
        public double avgValence;
        public double volatility;
        public String notablePattern;
    }

    public static final class PersonalitySnapshot {
        public LocalDate asOf;
        public Map<String, Double> scores = new LinkedHashMap<>();
        public List<String> evidenceIds = new ArrayList<>();
        public String changeNote;
    }

    public static final class PersonalityProfile {
        public Map<String, Double> current = new LinkedHashMap<>();
        public List<PersonalitySnapshot> history = new ArrayList<>();
    }

    public static final class ValueSnapshot {
        public LocalDate asOf;
        public Map<String, String> values = new LinkedHashMap<>();
        public List<String> evidenceIds = new ArrayList<>();
        public String changeNote;
    }

    public static final class ValueProfile {
        public Map<String, String> current = new LinkedHashMap<>();
        public List<ValueSnapshot> history = new ArrayList<>();
    }

    public enum HabitCategory { HEALTH, STUDY, WORK, HOBBY, SOCIAL, OTHER }
    public enum HabitStatus { ACTIVE, LAPSED, ABANDONED }

    public static final class HabitStreak {
        public int currentStreakDays;
        public int longestStreakDays;
        public LocalDate lastConfirmedAt;
    }

    public static final class HabitCheckpoint {
        public LocalDate asOf;
        public String note;
    }

    public static final class HabitRecord {
        public String id = UUID.randomUUID().toString();
        public String name;
        public HabitCategory category;
        public LocalDate firstObservedAt;
        public String frequencyPattern;
        public HabitStreak streak = new HabitStreak();
        public HabitStatus status = HabitStatus.ACTIVE;
        public List<HabitCheckpoint> history = new ArrayList<>();
    }

    public enum GoalStatus { ACTIVE, ACHIEVED, ABANDONED }

    public static final class Goal {
        public String id = UUID.randomUUID().toString();
        public String description;
        public LocalDate createdAt;
        public GoalStatus status = GoalStatus.ACTIVE;
        public List<String> evidenceIds = new ArrayList<>();
    }

    public static final class RelationshipEntry {
        public String label;
        public String note;
        public Instant lastMentionedAt;
        public List<String> evidenceIds = new ArrayList<>();
    }

    public static final class UserModel {
        public long userId;
        public Instant lastUpdatedAt;

        public PersonalityProfile personality = new PersonalityProfile();
        public ValueProfile values = new ValueProfile();
        public EmotionState currentEmotion;
        public TrendSummary emotionTrend;
        public List<HabitRecord> habits = new ArrayList<>();
        public List<Goal> goals = new ArrayList<>();
        public List<RelationshipEntry> relationships = new ArrayList<>();

        public List<String> openQuestions = new ArrayList<>();
        public String recentFocus;

        public UserModel() {}

        public UserModel(long userId) {
            this.userId = userId;
            this.lastUpdatedAt = Instant.now();
        }
    }
}
