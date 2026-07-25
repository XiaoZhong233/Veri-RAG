package com.example.verirag.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 小型 RAG 回答语义缓存。
 *
 * <p>缓存只复用相同分类范围内、相似度达到高阈值的问题，知识库变更后由文档服务整体失效。</p>
 */
@Slf4j
@Service
public class RagAnswerCache {

    private final EmbeddingModel embeddingModel;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final boolean enabled;
    private final double similarityThreshold;
    private final Duration ttl;
    private final int maxEntries;

    public RagAnswerCache(EmbeddingModel embeddingModel,
                          @Value("${rag.answer-cache.enabled:true}") boolean enabled,
                          @Value("${rag.answer-cache.similarity-threshold:0.93}") double similarityThreshold,
                          @Value("${rag.answer-cache.ttl-minutes:15}") long ttlMinutes,
                          @Value("${rag.answer-cache.max-entries:200}") int maxEntries) {
        this.embeddingModel = embeddingModel;
        this.enabled = enabled;
        this.similarityThreshold = similarityThreshold;
        this.ttl = Duration.ofMinutes(Math.max(ttlMinutes, 1));
        this.maxEntries = Math.max(maxEntries, 1);
    }

    public Optional<Hit> find(String question, Collection<Long> categoryIds) {
        if (!enabled || question == null || question.isBlank()) {
            return Optional.empty();
        }
        purgeExpired();
        String scope = scopeKey(categoryIds);
        String normalizedQuestion = normalize(question);
        Entry exact = entries.get(key(scope, normalizedQuestion));
        if (exact != null) {
            log.info("RAG answer cache hit: mode=exact, scope={}", scope);
            return Optional.of(new Hit(exact.answer, exact.references, 1.0));
        }

        List<Entry> candidates = entries.values().stream()
                .filter(entry -> entry.scope.equals(scope))
                .filter(entry -> entry.language.equals(languageBucket(question)))
                .toList();
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        try {
            float[] queryEmbedding = embeddingModel.embed(question);
            Entry best = null;
            double bestScore = -1;
            for (Entry candidate : candidates) {
                double score = cosineSimilarity(queryEmbedding, candidate.embedding);
                if (score > bestScore) {
                    bestScore = score;
                    best = candidate;
                }
            }
            if (best != null && bestScore >= similarityThreshold) {
                log.info("RAG answer cache hit: mode=semantic, similarity={}, scope={}",
                        String.format(Locale.ROOT, "%.3f", bestScore), scope);
                return Optional.of(new Hit(best.answer, best.references, bestScore));
            }
        }
        catch (Exception ex) {
            // 缓存不可用不能影响主问答链路。
            log.debug("RAG answer semantic cache lookup skipped: {}", ex.toString());
        }
        return Optional.empty();
    }

    public void put(String question, Collection<Long> categoryIds, String answer, List<Map<String, Object>> references) {
        if (!enabled || question == null || question.isBlank() || answer == null || answer.isBlank()) {
            return;
        }
        try {
            evictOverflow();
            String scope = scopeKey(categoryIds);
            Entry entry = new Entry(scope, languageBucket(question), answer,
                    List.copyOf(references == null ? List.of() : references),
                    embeddingModel.embed(question), Instant.now().plus(ttl));
            entries.put(key(scope, normalize(question)), entry);
        }
        catch (Exception ex) {
            log.debug("RAG answer cache write skipped: {}", ex.toString());
        }
    }

    public void evictAll() {
        int count = entries.size();
        entries.clear();
        if (count > 0) {
            log.info("RAG answer cache cleared: {} entries", count);
        }
    }

    private void purgeExpired() {
        Instant now = Instant.now();
        entries.entrySet().removeIf(entry -> entry.getValue().expiresAt.isBefore(now));
    }

    private void evictOverflow() {
        purgeExpired();
        int excess = entries.size() - maxEntries + 1;
        if (excess <= 0) {
            return;
        }
        entries.entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getValue().expiresAt))
                .limit(excess)
                .map(Map.Entry::getKey)
                .forEach(entries::remove);
    }

    private static String scopeKey(Collection<Long> categoryIds) {
        if (categoryIds == null || categoryIds.isEmpty()) {
            return "all";
        }
        return categoryIds.stream().filter(id -> id != null).distinct().sorted()
                .map(String::valueOf).reduce((left, right) -> left + "," + right).orElse("all");
    }

    private static String key(String scope, String question) {
        return scope + "|" + question;
    }

    private static String normalize(String question) {
        return question.toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Punct}]+", "");
    }

    static String languageBucket(String question) {
        if (question == null || question.isBlank()) {
            return "other";
        }
        long latinLetters = question.codePoints()
                .filter(codePoint -> (codePoint >= 'A' && codePoint <= 'Z')
                        || (codePoint >= 'a' && codePoint <= 'z'))
                .count();
        long chineseCharacters = question.codePoints()
                .filter(codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN)
                .count();
        if (latinLetters >= 3 && latinLetters > chineseCharacters * 2) {
            return "en";
        }
        return chineseCharacters > 0 ? "zh" : "other";
    }

    private static double cosineSimilarity(float[] left, float[] right) {
        if (left == null || right == null || left.length == 0 || left.length != right.length) {
            return -1;
        }
        double dot = 0, leftNorm = 0, rightNorm = 0;
        for (int i = 0; i < left.length; i++) {
            dot += (double) left[i] * right[i];
            leftNorm += (double) left[i] * left[i];
            rightNorm += (double) right[i] * right[i];
        }
        return leftNorm == 0 || rightNorm == 0 ? -1 : dot / Math.sqrt(leftNorm * rightNorm);
    }

    public record Hit(String answer, List<Map<String, Object>> references, double similarity) {
    }

    private record Entry(String scope, String language, String answer, List<Map<String, Object>> references,
                         float[] embedding, Instant expiresAt) {
    }
}
