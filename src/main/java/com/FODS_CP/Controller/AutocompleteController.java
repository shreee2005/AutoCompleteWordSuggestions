package com.FODS_CP.Controller;

import com.FODS_CP.data.FrequencyAwareTrie;
import com.FODS_CP.data.UserStore;
import com.FODS_CP.service.CategoryService;
import com.FODS_CP.service.NGramService;
import com.FODS_CP.service.Suggestion;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.github.benmanes.caffeine.cache.Cache;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
public class AutocompleteController {

    private final FrequencyAwareTrie trie;
    private final NGramService nGramService;
    private final UserStore userStore;
    private final Cache<String, Object> suggestionCache;
    private final MeterRegistry meterRegistry;

    @Autowired
    public AutocompleteController(FrequencyAwareTrie trie,
                                  NGramService nGramService,
                                  UserStore userStore,
                                  Cache<String, Object> suggestionCache,
                                  MeterRegistry meterRegistry) {
        this.trie = trie;
        this.nGramService = nGramService;
        this.userStore = userStore;
        this.suggestionCache = suggestionCache;
        this.meterRegistry = meterRegistry;
    }

    @GetMapping("/suggest")
    public ResponseEntity<SuggestResponse> suggest(
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "context", required = false) String context,
            @RequestParam(value = "limit", defaultValue = "6") int limit,
            @RequestParam(value = "userId", required = false) String userId
    ) {
        long start = System.currentTimeMillis();
        String prefix = (q == null) ? "" : q.trim();
        String ctx = (context == null) ? "" : context.trim();

        String cacheKey = "s:" + prefix.toLowerCase() + "|c:" + ctx.toLowerCase() + "|l:" + limit + "|u:" + (userId == null ? "" : userId);
        @SuppressWarnings("unchecked")
        Object cachedRaw = suggestionCache.getIfPresent(cacheKey);
        if (cachedRaw instanceof List) {
            try {
                @SuppressWarnings("unchecked")
                List<Suggestion> cached = (List<Suggestion>) cachedRaw;
                if (meterRegistry != null) meterRegistry.counter("autocomplete.requests", "result", "cache-hit").increment();
                long took = System.currentTimeMillis() - start;
                return ResponseEntity.ok(new SuggestResponse(prefix, cached, new Meta(true, "v1", took), null));
            } catch (Throwable ignored) {}
        }
        if (meterRegistry != null) meterRegistry.counter("autocomplete.requests", "result", "cache-miss").increment();

        // if prefix empty -> next-word candidates
        if (prefix.isEmpty()) {
            List<NGramService.Candidate> candidates = nGramService.getNextWordCandidates(ctx, Math.max(limit * 2, 10));
            List<Suggestion> out = new ArrayList<>();
            for (NGramService.Candidate c : candidates) {
                if (c == null || c.word == null) continue;
                String phrase = ctx.isEmpty() ? c.word : (ctx + " " + c.word);
                double cnt = getCandidateCountSafe(c);
                long freqProxy = Math.max(1, Math.round(cnt));
                Suggestion s = new Suggestion(phrase, freqProxy);
                double ngramScore = getCandidateProbSafe(c);
                double score = computeScore(s.getFrequency(), Math.log(ngramScore + 1e-9), s.getLastUsedEpochMillis(), 1.0, 0.0);
                s.setScore(score);
                out.add(s);
            }
            out.sort((a,b) -> Double.compare(b.getScore(), a.getScore()));
            List<Suggestion> top = out.size() > limit ? out.subList(0, limit) : out;
            suggestionCache.put(cacheKey, top);
            long took = System.currentTimeMillis() - start;
            if (meterRegistry != null) meterRegistry.timer("autocomplete.latency").record(took, java.util.concurrent.TimeUnit.MILLISECONDS);
            return ResponseEntity.ok(new SuggestResponse(prefix, top, new Meta(false, "v1", took), null));
        }

        // non-empty prefix: trie + ngram + fuzzy merge
        List<Suggestion> trieCandidates = trie.getSuggestions(prefix, Math.max(limit * 8, 30));
        List<NGramService.Candidate> ng = nGramService.getNextWordCandidates(ctx, 20);

        Map<String, Suggestion> bucket = new HashMap<>();
        if (trieCandidates != null) {
            for (Suggestion s : trieCandidates) {
                if (s != null && s.getText() != null) bucket.put(s.getText().toLowerCase(), s);
            }
        }

        if (ng != null) {
            for (NGramService.Candidate c : ng) {
                if (c == null || c.word == null) continue;
                String nextWord = c.word;
                if (!nextWord.toLowerCase().startsWith(prefix.toLowerCase())) continue;
                String phrase = ctx.isEmpty() ? nextWord : (ctx + " " + nextWord);
                if (!bucket.containsKey(phrase.toLowerCase())) {
                    long fp = Math.max(1, Math.round(getCandidateCountSafe(c)));
                    bucket.put(phrase.toLowerCase(), new Suggestion(phrase, fp));
                }
            }
        }

        if (trieCandidates == null || trieCandidates.isEmpty() || prefix.length() >= 2) {
            List<Suggestion> fuzzy = trie.getNearbyByFuzzy(prefix, 20);
            if (fuzzy != null) {
                for (Suggestion s : fuzzy) {
                    if (s == null) continue;
                    String display = ctx.isEmpty() ? s.getText() : (ctx + " " + s.getText());
                    bucket.putIfAbsent(display.toLowerCase(), new Suggestion(display, s.getFrequency()));
                }
            }
        }

        List<Suggestion> combined = new ArrayList<>(bucket.values());
        for (Suggestion s : combined) {
            if (s == null) continue;
            String display = s.getText();
            String suffixWord = display.contains(" ") ? display.substring(display.lastIndexOf(' ') + 1) : display;
            double ngramScore = findNGramScore(suffixWord, ng);
            double fuzzySim = computeFuzzySim(prefix, suffixWord);
            long lastUsed = s.getLastUsedEpochMillis();
            long freq = s.getFrequency();

            double personalBoost = 0.0;
            if (userId != null && !userId.isBlank()) {
                try {
                    Map<String,Integer> uc = userStore.getUser(userId);
                    if (uc != null) personalBoost = uc.getOrDefault(display.toLowerCase(), 0);
                } catch (Throwable ignored) { personalBoost = 0.0; }
            }

            double score = computeScore(freq, ngramScore, lastUsed, fuzzySim, personalBoost);
            s.setScore(score);
        }

        combined.sort((a,b) -> Double.compare(b.getScore(), a.getScore()));
        List<Suggestion> out = combined.size() > limit ? combined.subList(0, limit) : combined;

        // DID-YOU-MEAN (preferred trie.findDidYouMean then fuzzy fallback)
        String didYouMean = null;
        try {
            if (prefix != null && prefix.length() >= 2) {
                Optional<String> direct = trie.findDidYouMean(prefix);
                if (direct.isPresent() && !direct.get().equalsIgnoreCase(prefix)) {
                    didYouMean = direct.get();
                } else {
                    List<Suggestion> fuzzyList = trie.getNearbyByFuzzy(prefix, 5);
                    if (fuzzyList != null && !fuzzyList.isEmpty()) {
                        String candidate = fuzzyList.get(0).getText();
                        if (candidate != null && !candidate.equalsIgnoreCase(prefix)) {
                            double sim = com.FODS_CP.service.Fuzzy.similarity(prefix, candidate);
                            if (sim >= 0.7) didYouMean = candidate;
                        }
                    }
                }
            }
        } catch (Throwable t) {
            System.out.println("[DidYouMean] error: " + t.getMessage());
        }

        suggestionCache.put(cacheKey, out);
        long took = System.currentTimeMillis() - start;
        if (meterRegistry != null) meterRegistry.timer("autocomplete.latency").record(took, java.util.concurrent.TimeUnit.MILLISECONDS);
        return ResponseEntity.ok(new SuggestResponse(prefix, out, new Meta(false, "v1", took), didYouMean));
    }

    @PostMapping("/accept")
    public ResponseEntity<Void> accept(@RequestBody AcceptRequest req) {
        if (req == null || req.getUserId() == null || req.getSelected() == null) return ResponseEntity.badRequest().build();
        try { userStore.increment(req.getUserId(), req.getSelected()); } catch (Throwable ignored) {}
        try { trie.insert(req.getSelected(), 1); } catch (Throwable ignored) {}
        return ResponseEntity.ok().build();
    }

    @GetMapping("/trending")
    public ResponseEntity<List<String>> trending(@RequestParam(value="limit", defaultValue = "10") int limit) {
        Map<String, Long> dict = trie.getVocabulary();
        List<String> list = dict.entrySet().stream()
                .sorted(Map.Entry.<String,Long>comparingByValue().reversed())
                .limit(limit)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
        return ResponseEntity.ok(list);
    }

    // helpers
    private double findNGramScore(String candidate, List<NGramService.Candidate> ng) {
        if (ng == null) return 0.0;
        for (NGramService.Candidate c : ng) {
            if (c == null) continue;
            if (c.word != null && c.word.equalsIgnoreCase(candidate)) {
                double v = getCandidateCountSafe(c);
                if (v > 0) return Math.log(v + 1);
                return getCandidateProbSafe(c);
            }
        }
        return 0.0;
    }

    private double computeFuzzySim(String q, String candidate) {
        try { return com.FODS_CP.service.Fuzzy.similarity(q, candidate); } catch (Throwable t) { return candidate.toLowerCase().startsWith(q.toLowerCase()) ? 1.0 : 0.0; }
    }

    private double computeScore(long frequency, double ngramProb, long lastUsedEpochMillis, double fuzzySim, double personalBoost) {
        double alpha = 1.0, beta = 1.2, gamma = 0.6, delta = 1.0;
        double personal = Math.log(1 + personalBoost * 6.0);
        double freqPart = Math.log(frequency + 1);
        double recencyDays = lastUsedEpochMillis > 0 ? (System.currentTimeMillis() - lastUsedEpochMillis) / (1000.0 * 60 * 60 * 24) : 3650.0;
        double recencyBoost = 1.0 / (1.0 + recencyDays);
        return alpha * freqPart + beta * ngramProb + gamma * recencyBoost + delta * fuzzySim + personal;
    }

    private double getCandidateCountSafe(NGramService.Candidate c) {
        try { return c.count; } catch (Throwable ignored) {}
        try {
            var f = c.getClass().getDeclaredField("count");
            f.setAccessible(true);
            Object v = f.get(c);
            if (v instanceof Number) return ((Number)v).doubleValue();
        } catch (Throwable ignored) {}
        return 0.0;
    }

    private double getCandidateProbSafe(NGramService.Candidate c) {
        try { return c.count; } catch (Throwable ignored) {}
        return getCandidateCountSafe(c);
    }

    // DTOs
    public static class AcceptRequest {
        private String userId;
        private String selected;
        public String getUserId() { return userId; }
        public void setUserId(String userId) { this.userId = userId; }
        public String getSelected() { return selected; }
        public void setSelected(String selected) { this.selected = selected; }
    }

    public static class SuggestResponse {
        private String prefix;
        private List<Suggestion> suggestions;
        private Meta meta;
        private String didYouMean;
        public SuggestResponse(String prefix, List<Suggestion> suggestions, Meta meta, String didYouMean) {
            this.prefix = prefix; this.suggestions = suggestions; this.meta = meta; this.didYouMean = didYouMean;
        }
        public String getPrefix() { return prefix; }
        public List<Suggestion> getSuggestions() { return suggestions; }
        public Meta getMeta() { return meta; }
        public String getDidYouMean() { return didYouMean; }
    }

    public static class Meta {
        private boolean fromCache;
        private String modelVersion;
        private long tookMs;
        public Meta(boolean fromCache, String modelVersion, long tookMs) { this.fromCache = fromCache; this.modelVersion = modelVersion; this.tookMs = tookMs; }
        public boolean isFromCache() { return fromCache; }
        public String getModelVersion() { return modelVersion; }
        public long getTookMs() { return tookMs; }
    }
}
