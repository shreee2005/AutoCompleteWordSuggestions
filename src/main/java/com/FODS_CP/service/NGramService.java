package com.FODS_CP.service;

import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minimal NGramService used for next-word prediction.
 * - Candidate: simple DTO { word, count }
 * - getNextWordCandidates(context, limit) returns candidates for the last token in context
 *
 * Optional CSV loader: place classpath resource /ngrams.csv with lines:
 *   how,are,120
 *   how,is,60
 *   i,am,200
 *
 * If CSV not present, demo data is loaded so service works out-of-the-box.
 */
@Service
public class NGramService {

    public static class Candidate {
        public final String word;
        public final long count;
        public Candidate(String word, long count){ this.word = word; this.count = count; }
        @Override public String toString(){ return word + ":" + count; }
    }

    // map: context token (lower) -> map(nextWord -> count)
    private final Map<String, Map<String, Long>> nextWordMap = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        boolean loaded = loadFromClasspathCsv("/ngrams.csv");
        if (!loaded) {
            loadDemoData();
        }
    }

    private boolean loadFromClasspathCsv(String resourcePath) {
        try {
            InputStream is = getClass().getResourceAsStream(resourcePath);
            if (is == null) return false;
            try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    String[] parts = line.split(",");
                    if (parts.length < 3) continue;
                    String ctx = parts[0].trim().toLowerCase();
                    String next = parts[1].trim();
                    long cnt = 1;
                    try { cnt = Long.parseLong(parts[2].trim()); } catch (Exception ignored) {}
                    addBigram(ctx, next, cnt);
                }
            }
            System.out.println("[NGramService] loaded ngrams from " + resourcePath);
            return true;
        } catch (Throwable t) {
            System.out.println("[NGramService] failed loading " + resourcePath + " : " + t.getMessage());
            return false;
        }
    }

    private void loadDemoData() {
        addBigram("how", "are", 220);
        addBigram("how", "is", 110);
        addBigram("how", "do", 60);

        addBigram("i", "am", 300);
        addBigram("i", "have", 150);
        addBigram("i", "will", 80);

        addBigram("thank", "you", 500);
        addBigram("looking", "forward", 80);

        System.out.println("[NGramService] demo ngrams loaded");
    }

    /**
     * Adds or increments a bigram count.
     * contextToken should be a single token (lowercase recommended).
     */
    public void addBigram(String contextToken, String nextWord, long count) {
        if (contextToken == null || contextToken.isBlank() || nextWord == null || nextWord.isBlank()) return;
        String key = contextToken.trim().toLowerCase();
        nextWordMap.computeIfAbsent(key, k -> new ConcurrentHashMap<>())
                .merge(nextWord, Math.max(1, count), Long::sum);
    }

    /**
     * Return top candidates for the last token of context.
     * If context empty returns empty list.
     */
    public List<Candidate> getNextWordCandidates(String context, int limit) {
        if (context == null || context.isBlank()) return Collections.emptyList();
        String[] parts = context.trim().split("\\s+");
        if (parts.length == 0) return Collections.emptyList();
        String last = parts[parts.length - 1].toLowerCase();
        Map<String, Long> map = nextWordMap.get(last);
        if (map == null || map.isEmpty()) return Collections.emptyList();

        return map.entrySet().stream()
                .sorted(Map.Entry.<String,Long>comparingByValue().reversed())
                .limit(Math.max(1, limit))
                .map(e -> new Candidate(e.getKey(), e.getValue()))
                .toList();
    }

    /** Expose internal map snapshot for debug */
    public Map<String, Map<String, Long>> getSnapshot() {
        Map<String, Map<String, Long>> out = new HashMap<>();
        for (Map.Entry<String, Map<String, Long>> e : nextWordMap.entrySet()) {
            out.put(e.getKey(), new HashMap<>(e.getValue()));
        }
        return out;
    }
}
