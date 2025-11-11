package com.FODS_CP.data;

import com.FODS_CP.service.Suggestion;
import com.FODS_CP.service.Fuzzy;
import org.apache.commons.text.similarity.LevenshteinDistance;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Service
public class FrequencyAwareTrie {

    private static class Node {
        Map<Character, Node> children = new HashMap<>();
        boolean end = false;
        long freq = 0L;
    }

    private final Node root = new Node();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final LevenshteinDistance levenshtein = new LevenshteinDistance(5);

    public FrequencyAwareTrie() {}

    public void insert(String word, long frequency) {
        if (word == null || word.isEmpty()) return;
        lock.writeLock().lock();
        try {
            Node cur = root;
            for (char ch : word.toCharArray()) {
                cur = cur.children.computeIfAbsent(ch, k -> new Node());
            }
            cur.end = true;
            cur.freq = Math.max(cur.freq, frequency); // keep higher frequency
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<Suggestion> getSuggestions(String prefix, int limit) {
        if (prefix == null) prefix = "";
        if (limit <= 0) limit = 10;
        lock.readLock().lock();
        try {
            Node node = findNode(prefix);
            if (node == null) return Collections.emptyList();
            PriorityQueue<Suggestion> pq = new PriorityQueue<>(Comparator.comparingLong(Suggestion::getFrequency));
            collect(node, prefix, pq, limit);
            List<Suggestion> out = new ArrayList<>();
            while (!pq.isEmpty()) out.add(pq.poll());
            Collections.reverse(out);
            return out;
        } finally {
            lock.readLock().unlock();
        }
    }

    // helper collect into min-heap
    private void collect(Node node, String curPrefix, PriorityQueue<Suggestion> pq, int limit) {
        if (node == null) return;
        if (node.end) {
            Suggestion s = new Suggestion(curPrefix, node.freq);
            if (pq.size() < limit) pq.offer(s);
            else if (s.getFrequency() > pq.peek().getFrequency()) {
                pq.poll();
                pq.offer(s);
            }
        }
        for (Map.Entry<Character, Node> e : node.children.entrySet()) {
            collect(e.getValue(), curPrefix + e.getKey(), pq, limit);
        }
    }

    private Node findNode(String prefix) {
        Node cur = root;
        for (char ch : prefix.toCharArray()) {
            if (cur == null) return null;
            cur = cur.children.get(ch);
            if (cur == null) return null;
        }
        return cur;
    }

    /**
     * Fuzzy search over vocabulary using Levenshtein (bounded distance).
     * Returns suggestions ordered by frequency desc.
     */
    public List<Suggestion> getNearbyByFuzzy(String token, int limit) {
        if (token == null || token.isEmpty()) return Collections.emptyList();
        token = token.toLowerCase();
        // collect vocabulary snapshot
        Map<String, Long> vocab = getVocabulary();
        List<Suggestion> matches = new ArrayList<>();
        for (Map.Entry<String, Long> e : vocab.entrySet()) {
            String w = e.getKey();
            int d = levenshtein.apply(w, token);
            if (d != -1) {
                matches.add(new Suggestion(w, e.getValue()));
            }
        }
        matches.sort(Comparator.comparingLong(Suggestion::getFrequency).reversed());
        return matches.size() > limit ? matches.subList(0, limit) : matches;
    }

    /**
     * Try to return a best correction for token from vocabulary using Fuzzy.similarity.
     * Returns Optional.empty() when not confident.
     */
    public Optional<String> findDidYouMean(String token) {
        if (token == null || token.length() < 2) return Optional.empty();
        Map<String, Long> vocab = getVocabulary();
        String best = null;
        double bestScore = 0.0;
        for (Map.Entry<String, Long> e : vocab.entrySet()) {
            String w = e.getKey();
            double sim = Fuzzy.similarity(token, w);
            // bias by frequency a bit
            double score = sim + Math.log(e.getValue() + 1) / 50.0;
            if (score > bestScore) {
                bestScore = score;
                best = w;
            }
        }
        // require reasonable similarity to consider as suggestion
        if (best != null && bestScore >= 0.65) {
            return Optional.of(best);
        }
        return Optional.empty();
    }

    /**
     * Return a snapshot of vocabulary -> frequency.
     * Note: expensive for huge vocabularies but fine for demo / moderate datasets.
     */
    public Map<String, Long> getVocabulary() {
        Map<String, Long> out = new HashMap<>();
        lock.readLock().lock();
        try {
            traverseCollect(root, new StringBuilder(), out);
        } finally {
            lock.readLock().unlock();
        }
        return out;
    }

    private void traverseCollect(Node node, StringBuilder cur, Map<String, Long> out) {
        if (node == null) return;
        if (node.end) {
            out.put(cur.toString(), node.freq);
        }
        for (Map.Entry<Character, Node> e : node.children.entrySet()) {
            cur.append(e.getKey());
            traverseCollect(e.getValue(), cur, out);
            cur.deleteCharAt(cur.length() - 1);
        }
    }

    // helper exposing fine-grained map if needed
    public Map<String, Long> getWordFrequencyMap() {
        return getVocabulary();
    }
}
