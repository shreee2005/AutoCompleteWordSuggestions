package com.FODS_CP.data;

import com.FODS_CP.service.Suggestion;
import com.FODS_CP.TrieNode;
import org.apache.commons.text.similarity.LevenshteinDistance;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

@Service
public class FrequencyAwareTrie {
    private final WordFrequencyService wordFrequencyService;
    private final LevenshteinDistance levenshteinDistance;
    private final TrieNode root;
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();

    private static final int DEFAULT_SUGGESTION_LIMIT = 10;
    private static final int DEFAULT_FUZZY_MAX_DISTANCE = 2;

    public FrequencyAwareTrie(WordFrequencyService wordFrequencyService) {
        this.wordFrequencyService = wordFrequencyService;
        this.levenshteinDistance = new LevenshteinDistance(DEFAULT_FUZZY_MAX_DISTANCE);
        this.root = new TrieNode();
    }

    public void insert(String word, long frequency) {
        if (word == null || word.isEmpty()) return;
        rwLock.writeLock().lock();
        try {
            TrieNode current = root;
            for (char ch : word.toCharArray()) {
                current = current.getChildren().computeIfAbsent(ch, c -> new TrieNode());
            }
            current.setEndOfWord(true);
            long existing = current.getFrequency();
            long newFreq = Math.max(existing, frequency);
            current.setFrequency(newFreq);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    public List<Suggestion> getSuggestions(String prefix) {
        return getSuggestions(prefix, DEFAULT_SUGGESTION_LIMIT);
    }

    public List<Suggestion> getSuggestions(String prefix, int limit) {
        if (prefix == null) prefix = "";
        if (limit <= 0) limit = DEFAULT_SUGGESTION_LIMIT;
        return getTopSuggestions(prefix, limit);
    }

    public List<Suggestion> getTopSuggestions(String prefix, int limit) {
        if (prefix == null) prefix = "";
        if (limit <= 0) limit = DEFAULT_SUGGESTION_LIMIT;
        rwLock.readLock().lock();
        try {
            TrieNode prefixNode = findNodeForPrefix(prefix);
            if (prefixNode == null) return Collections.emptyList();

            PriorityQueue<Suggestion> pq = new PriorityQueue<>(Comparator.comparingLong(Suggestion::getFrequency));
            collectWords(prefixNode, prefix, pq, limit);

            List<Suggestion> out = new ArrayList<>();
            while (!pq.isEmpty()) out.add(pq.poll());
            Collections.reverse(out); // highest frequency first
            return out;
        } finally {
            rwLock.readLock().unlock();
        }
    }

    private void collectWords(TrieNode node, String currentPrefix, PriorityQueue<Suggestion> pq, int limit) {
        if (node == null) return;
        if (node.isEndOfWord()) {
            Suggestion s = new Suggestion(currentPrefix, node.getFrequency());
            if (pq.size() < limit) {
                pq.offer(s);
            } else if (s.getFrequency() > pq.peek().getFrequency()) {
                pq.poll();
                pq.offer(s);
            }
        }
        for (Map.Entry<Character, TrieNode> e : node.getChildren().entrySet()) {
            collectWords(e.getValue(), currentPrefix + e.getKey(), pq, limit);
        }
    }

    private TrieNode findNodeForPrefix(String prefix) {
        TrieNode current = root;
        for (char ch : prefix.toCharArray()) {
            if (current == null) return null;
            current = current.getChildren().get(ch);
            if (current == null) return null;
        }
        return current;
    }

    public List<String> getFuzzySuggestions(String missSpelledWord) {
        return getFuzzySuggestions(missSpelledWord, DEFAULT_SUGGESTION_LIMIT);
    }

    public List<String> getFuzzySuggestions(String missSpelledWord, int limit) {
        if (missSpelledWord == null || missSpelledWord.isEmpty()) return Collections.emptyList();
        List<Suggestion> matches = new ArrayList<>();
        Map<String, Long> dict = wordFrequencyService.getWordFrequencies();
        for (Map.Entry<String, Long> e : dict.entrySet()) {
            String word = e.getKey();
            int dist = levenshteinDistance.apply(word, missSpelledWord);
            if (dist != -1) {
                matches.add(new Suggestion(word, e.getValue()));
            }
        }
        return matches.stream()
                .sorted(Comparator.comparingLong(Suggestion::getFrequency).reversed())
                .limit(limit)
                .map(Suggestion::getText)
                .collect(Collectors.toList());
    }

    public List<Suggestion> getNearbyByFuzzy(String prefix, int limit) {
        if (prefix == null) prefix = "";
        if (limit <= 0) limit = DEFAULT_SUGGESTION_LIMIT;

        rwLock.readLock().lock();
        try {
            TrieNode start = findNodeForPrefix(prefix);
            List<Suggestion> collected = new ArrayList<>();
            if (start != null) {
                String finalPrefix = prefix;
                PriorityQueue<Suggestion> pq = new PriorityQueue<>(Comparator.comparingDouble(s -> scoreForFuzzyHeap(s, finalPrefix)));
                collectWordsForFuzzy(start, prefix, pq, limit);
                while (!pq.isEmpty()) collected.add(pq.poll());
                Collections.reverse(collected);
                return collected;
            } else {
                char first = prefix.isEmpty() ? 0 : prefix.charAt(0);
                String finalPrefix1 = prefix;
                PriorityQueue<Suggestion> pq = new PriorityQueue<>(Comparator.comparingDouble(s -> scoreForFuzzyHeap(s, finalPrefix1)));
                Map<String, Long> dict = wordFrequencyService.getWordFrequencies();
                for (Map.Entry<String, Long> e : dict.entrySet()) {
                    String w = e.getKey();
                    if (!prefix.isEmpty() && w.isEmpty()) continue;
                    if (!prefix.isEmpty() && w.charAt(0) != first) continue; // small prune
                    int dist = levenshteinDistance.apply(w, prefix);
                    if (dist != -1) {
                        Suggestion s = new Suggestion(w, e.getValue());
                        if (pq.size() < limit) pq.offer(s);
                        else if (scoreForFuzzyHeap(s, prefix) > scoreForFuzzyHeap(pq.peek(), prefix)) {
                            pq.poll();
                            pq.offer(s);
                        }
                    }
                }
                while (!pq.isEmpty()) collected.add(pq.poll());
                Collections.reverse(collected);
                return collected;
            }
        } finally {
            rwLock.readLock().unlock();
        }
    }

    private void collectWordsForFuzzy(TrieNode node, String currentPrefix, PriorityQueue<Suggestion> pq, int limit) {
        if (node == null) return;
        if (node.isEndOfWord()) {
            Suggestion s = new Suggestion(currentPrefix, node.getFrequency());
            if (pq.size() < limit) pq.offer(s);
            else if (scoreForFuzzyHeap(s, currentPrefix) > scoreForFuzzyHeap(pq.peek(), currentPrefix)) {
                pq.poll();
                pq.offer(s);
            }
        }
        for (Map.Entry<Character, TrieNode> e : node.getChildren().entrySet()) {
            collectWordsForFuzzy(e.getValue(), currentPrefix + e.getKey(), pq, limit);
        }
    }

    /**
     * Return a snapshot map of word -> frequency for trending endpoint.
     * This traverses trie and collects words. For demo scale it's acceptable.
     */
    public Map<String, Long> getWordFrequencyMap() {
        Map<String, Long> out = new HashMap<>();
        rwLock.readLock().lock();
        try {
            // DFS traversal
            Deque<TrieNode> stack = new ArrayDeque<>();
            Deque<String> prefixStack = new ArrayDeque<>();
            stack.push(root);
            prefixStack.push("");
            while (!stack.isEmpty()) {
                TrieNode node = stack.pop();
                String pref = prefixStack.pop();
                if (node.isEndOfWord() && node.getFrequency() > 0) {
                    out.put(pref, node.getFrequency());
                }
                for (Map.Entry<Character, TrieNode> e : node.getChildren().entrySet()) {
                    stack.push(e.getValue());
                    prefixStack.push(pref + e.getKey());
                }
            }
        } finally {
            rwLock.readLock().unlock();
        }
        return out;
    }

    /**
     * Scoring used for fuzzy ordering:
     * preference to higher frequency and smaller Levenshtein distance.
     */
    private double scoreForFuzzyHeap(Suggestion s, String target) {
        int dist = levenshteinDistance.apply(s.getText(), target);
        if (dist == -1) dist = DEFAULT_FUZZY_MAX_DISTANCE + 1;
        double sim = 1.0 / (1 + dist); // higher for smaller distance
        return sim * Math.log(s.getFrequency() + 1);
    }
}
