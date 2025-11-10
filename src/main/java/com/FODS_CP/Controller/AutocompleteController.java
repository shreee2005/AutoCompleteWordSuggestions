package com.FODS_CP.Controller;

import com.FODS_CP.data.FrequencyAwareTrie;
import com.FODS_CP.data.UserStore;
import com.FODS_CP.service.CategoryService;
import com.FODS_CP.service.NGramService;
import com.FODS_CP.service.Suggestion;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
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

    // simple in-memory per-user counts for demo (backed by UserStore)
    private final Map<String, Map<String, Integer>> userCounts = new ConcurrentHashMap<>();

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
        String prefix = (q == null) ? "" : q.trim();          // current token being typed
        String ctx = (context == null) ? "" : context.trim(); // left-side words (may be empty)

        // canonical cache key
        String cacheKey = "s:" + prefix.toLowerCase() + "|c:" + ctx.toLowerCase() + "|l:" + limit + "|u:" + (userId == null ? "" : userId);
        @SuppressWarnings("unchecked")
        Object cachedRaw = suggestionCache.getIfPresent(cacheKey);
        if (cachedRaw instanceof List) {
            try {
                @SuppressWarnings("unchecked")
                List<Suggestion> cached = (List<Suggestion>) cachedRaw;
                meterRegistry.counter("autocomplete.requests", "result", "cache-hit").increment();
                long took = System.currentTimeMillis() - start;
                return ResponseEntity.ok(new SuggestResponse(prefix, cached, new Meta(true, "v1", took)));
            } catch (Throwable t) {
                // fall through and recompute if casting failed
            }
        }
        // cache miss
        meterRegistry.counter("autocomplete.requests", "result", "cache-miss").increment();

        // If prefix is empty -> user typed a space => provide next-word phrase suggestions from n-gram
        if (prefix.isEmpty()) {
            List<NGramService.Candidate> candidates = nGramService.getNextWordCandidates(ctx, Math.max(limit * 2, 10));
            List<Suggestion> out = new ArrayList<>();
            for (NGramService.Candidate c : candidates) {
                // Build full phrase (context + nextWord). If ctx empty, phrase is just the nextWord.
                // NOTE: this assumes Candidate has fields 'word' and 'count'. If your Candidate uses 'prob' instead,
                // adapt this line accordingly in your NGramService.
                String phrase = ctx.isEmpty() ? c.word : (ctx + " " + c.word);
                long freqProxy = (long) ( (c instanceof NGramService.Candidate) ? ( (long) (Double.isNaN(getCandidateCountSafe(c)) ? 0L : getCandidateCountSafe(c)) ) : 0L );
                // fallback: use 1 if we cannot extract numeric count
                if (freqProxy <= 0) freqProxy = 1L;
                Suggestion s = new Suggestion(phrase, freqProxy);
                double ngramScore = getCandidateProbSafe(c);
                double score = computeScore(s.getFrequency(), Math.log(ngramScore + 1e-9), s.getLastUsedEpochMillis(), 1.0, 0.0);
                s.setScore(score);
                out.add(s);
            }
            out.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));
            List<Suggestion> top = out.size() > limit ? out.subList(0, limit) : out;
            suggestionCache.put(cacheKey, top);
            long took = System.currentTimeMillis() - start;
            meterRegistry.timer("autocomplete.latency").record(took, java.util.concurrent.TimeUnit.MILLISECONDS);
            return ResponseEntity.ok(new SuggestResponse(prefix, top, new Meta(false, "v1", took)));
        }

        // --- prefix non-empty: combine trie suggestions for this prefix (word completions)
        // --- with n-gram next-word candidates that match this prefix (i.e., beginning characters of next word)
        List<Suggestion> trieCandidates = trie.getSuggestions(prefix, Math.max(limit * 8, 30));

        // n-gram candidates are next-word predictions based on ctx
        List<NGramService.Candidate> ng = nGramService.getNextWordCandidates(ctx, 20);

        // Merge bucket keyed by displayText (lowercase). We will create phrase text for n-grams when ctx present.
        Map<String, Suggestion> bucket = new HashMap<>();
        // Add trie completions (these are single words; we'll convert to phrase if context exists when returning)
        if (trieCandidates != null) {
            for (Suggestion s : trieCandidates) {
                if (s != null && s.getText() != null) {
                    bucket.put(s.getText().toLowerCase(), s);
                }
            }
        }

        // Add n-gram candidates but only those that match the prefix (startWith)
        if (ng != null) {
            for (NGramService.Candidate c : ng) {
                if (c == null || c.word == null) continue;
                String nextWord = c.word;
                if (!nextWord.toLowerCase().startsWith(prefix.toLowerCase())) {
                    continue; // ignore n-gram words that don't match the user-typed prefix
                }
                // Build phrase display text
                String phrase = ctx.isEmpty() ? nextWord : (ctx + " " + nextWord);
                if (!bucket.containsKey(phrase.toLowerCase())) {
                    long freqProxy = (long) Math.max(1, Math.round(getCandidateCountSafe(c)));
                    bucket.put(phrase.toLowerCase(), new Suggestion(phrase, freqProxy));
                }
            }
        }

        // Fuzzy: apply fuzzy only against the current token (prefix), not the whole input.
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

        // Scoring & personalization: compute scores for display phrases
        List<Suggestion> combined = new ArrayList<>(bucket.values());
        for (int i = 0; i < combined.size(); i++) {
            Suggestion s = combined.get(i);
            if (s == null) continue;
            // compute suffix and ngramScore
            String display = s.getText();
            String suffixWord = display.contains(" ") ? display.substring(display.lastIndexOf(' ') + 1) : display;
            double ngramScore = findNGramScore(suffixWord, ng); // uses candidate word counts
            double fuzzySim = computeFuzzySim(prefix, suffixWord); // fuzzy between current token and suffix
            long lastUsed = s.getLastUsedEpochMillis();
            long freq = s.getFrequency();

            double personalBoost = 0.0;
            if (userId != null && !userId.isBlank()) {
                try {
                    Map<String,Integer> uc = userStore.getUser(userId);
                    if (uc != null) personalBoost = uc.getOrDefault(display.toLowerCase(), 0);
                } catch (Throwable ignore) {
                    // defensive: if userStore API differs, fall back to zero
                    personalBoost = 0.0;
                }
            }

            double score = computeScore(freq, ngramScore, lastUsed, fuzzySim, personalBoost);
            s.setScore(score);
            combined.set(i, s);
        }

        combined.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));
        List<Suggestion> out = combined.size() > limit ? combined.subList(0, limit) : combined;

        suggestionCache.put(cacheKey, out);
        long took = System.currentTimeMillis() - start;
        meterRegistry.timer("autocomplete.latency").record(took, java.util.concurrent.TimeUnit.MILLISECONDS);
        return ResponseEntity.ok(new SuggestResponse(prefix, out, new Meta(false, "v1", took)));
    }



    @PostMapping("/accept")
    public ResponseEntity<Void> accept(@RequestBody AcceptRequest req) {
        if (req == null || req.getUserId() == null || req.getSelected() == null) return ResponseEntity.badRequest().build();
        try {
            userStore.increment(req.getUserId(), req.getSelected());
        } catch (Throwable t) {
            // defensive: if UserStore wiring not ready, keep going
            System.err.println("Warning: userStore.increment failed: " + t.getMessage());
        }
        // update trie lightly (demo) - increment frequency locally
        try {
            trie.insert(req.getSelected(), 1);
        } catch (Throwable t) {
            // ignore
        }
        return ResponseEntity.ok().build();
    }

    @Autowired
    private CategoryService categoryService;

    @GetMapping("/grouped")
    public ResponseEntity<Map<String,List<String>>> grouped(@RequestParam("q") String q) {
        List<Suggestion> sugg = trie.getSuggestions(q, 20);
        List<String> words = sugg.stream().map(Suggestion::getText).collect(Collectors.toList());
        return ResponseEntity.ok(categoryService.groupByCategory(words));
    }

    // --- trending endpoint for demo (top N by global frequency) ---
    @GetMapping("/trending")
    public ResponseEntity<List<String>> trending(@RequestParam(value="limit", defaultValue = "10") int limit) {
        Map<String, Long> dict = trie.getWordFrequencyMap(); // helper in trie expected
        List<String> list = dict.entrySet().stream()
                .sorted(Map.Entry.<String,Long>comparingByValue().reversed())
                .limit(limit)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
        return ResponseEntity.ok(list);
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String,Object>> stats() {
        Map<String,Object> m = new LinkedHashMap<>();
        double cacheHits = 0.0;
        double cacheMiss = 0.0;
        try {
            if (meterRegistry != null) {
                var ch = meterRegistry.find("autocomplete.requests").tag("result","cache-hit").counter();
                if (ch != null) cacheHits = ch.count();
                var cm = meterRegistry.find("autocomplete.requests").tag("result","cache-miss").counter();
                if (cm != null) cacheMiss = cm.count();
            }
        } catch (Throwable t) {
            // ignore and return zeros
        }
        m.put("cacheHits", cacheHits);
        m.put("cacheMiss", cacheMiss);
        m.put("requestsTotal", cacheHits + cacheMiss);
        return ResponseEntity.ok(m);
    }

    // helpers unchanged (copy from your prior controller)
    private double findNGramScore(String candidate, List<NGramService.Candidate> ng) {
        if (ng == null || ng.isEmpty()) return 0.0;
        for (NGramService.Candidate c : ng) {
            if (c.word.equalsIgnoreCase(candidate)) {
                // try to use count if present, otherwise prob
                double val = getCandidateCountSafe(c);
                if (val > 0) return Math.log(val + 1);
                return getCandidateProbSafe(c);
            }
        }
        return 0.0;
    }

    private double computeFuzzySim(String q, String candidate) {
        try {
            return com.FODS_CP.service.Fuzzy.similarity(q, candidate);
        } catch (Throwable t) {
            return candidate.toLowerCase().startsWith(q.toLowerCase()) ? 1.0 : 0.0;
        }
    }

    private double computeScore(long frequency, double ngramProb, long lastUsedEpochMillis, double fuzzySim, double personalBoost) {
        double alpha = 1.0;
        double beta = 1.2;
        double gamma = 0.6;
        double delta = 1.0;
        double personal = Math.log(personalBoost + 1);

        double freqPart = Math.log(frequency + 1);
        double recencyDays = lastUsedEpochMillis > 0 ? (System.currentTimeMillis() - lastUsedEpochMillis) / (1000.0 * 60 * 60 * 24) : 3650.0;
        double recencyBoost = 1.0 / (1.0 + recencyDays);

        return alpha * freqPart + beta * ngramProb + gamma * recencyBoost + delta * fuzzySim + personal;
    }

    // Utility: try to extract numeric 'count' from candidate (works with multiple shapes)
    private double getCandidateCountSafe(NGramService.Candidate c) {
        try {
            // If Candidate has a 'count' field (int/long)
            var field = c.getClass().getDeclaredField("count");
            field.setAccessible(true);
            Object v = field.get(c);
            if (v instanceof Number) return ((Number) v).doubleValue();
        } catch (Throwable ignored) {}

        try {
            // If Candidate has 'prob' or 'score' or 'p' as double
            var f2 = c.getClass().getDeclaredField("prob");
            f2.setAccessible(true);
            Object v = f2.get(c);
            if (v instanceof Number) return ((Number) v).doubleValue();
        } catch (Throwable ignored) {}

        return 0.0;
    }

    // Utility: extract probability-like value if present (fallbacks)
    private double getCandidateProbSafe(NGramService.Candidate c) {
        try {
            var field = c.getClass().getDeclaredField("prob");
            field.setAccessible(true);
            Object v = field.get(c);
            if (v instanceof Number) return ((Number) v).doubleValue();
        } catch (Throwable ignored) {}

        // fallback to count
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
        public SuggestResponse(String prefix, List<Suggestion> suggestions, Meta meta) {
            this.prefix = prefix; this.suggestions = suggestions; this.meta = meta;
        }
        public String getPrefix() { return prefix; }
        public List<Suggestion> getSuggestions() { return suggestions; }
        public Meta getMeta() { return meta; }
    }

    public static class Meta {
        private boolean fromCache;
        private String modelVersion;
        private long tookMs;
        public Meta(boolean fromCache, String modelVersion, long tookMs) {
            this.fromCache = fromCache; this.modelVersion = modelVersion; this.tookMs = tookMs;
        }
        public boolean isFromCache() { return fromCache; }
        public String getModelVersion() { return modelVersion; }
        public long getTookMs() { return tookMs; }
    }
}
