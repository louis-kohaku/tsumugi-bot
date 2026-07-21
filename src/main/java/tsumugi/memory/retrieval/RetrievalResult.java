package tsumugi.memory.retrieval;

import tsumugi.core.model.TsumugiModel.Evidence;

/** MemoryRetrieverの検索結果1件。スコアはconfidence込みの最終順位付け用の値。 */
public record RetrievalResult(Evidence evidence, double score) {}
