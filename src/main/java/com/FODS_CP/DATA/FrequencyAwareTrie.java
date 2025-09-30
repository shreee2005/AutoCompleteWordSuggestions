package com.FODS_CP.DATA;


import com.FODS_CP.Suggestion;
import com.FODS_CP.TrieNode;
import jdk.jshell.SourceCodeAnalysis;
import org.springframework.core.PriorityOrdered;
import org.springframework.stereotype.Service;

import java.security.cert.PolicyNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class FrequencyAwareTrie {

    private final TrieNode root;
    private static final int SUGGESTION_LIMIT = 10;

    public FrequencyAwareTrie() {
        // The root node is empty and doesn't represent any character.
        root = new TrieNode();

    }

    /**
     * Inserts a word into the Trie along with its frequency.
     * @param word The word to insert.
     * @param frequency The frequency of the word.
     */
    public void insert(String word, long frequency) {
        TrieNode current = root;
        for (char ch : word.toCharArray()) {
            current = current.getChildren().computeIfAbsent(ch, c -> new TrieNode());
        }

        current.setEndOfWord(true);
        current.setFrequency(frequency);
    }
    public List<String> getSuggestions(String prefix){
        TrieNode prefixNode = findNodeForPrefix(prefix);

        if(prefixNode == null){
            return Collections.emptyList();
        }
        List<Suggestion> allSuggestions = new ArrayList<>();
        collectWords(prefixNode ,prefix , allSuggestions);

        return allSuggestions.stream().sorted(Comparator.comparingLong(Suggestion::frequency).reversed())
                .limit(SUGGESTION_LIMIT)
                .map(Suggestion::word)
                .collect(Collectors.toList());

    }

    private void collectWords(TrieNode prefixNode, String prefix, List<Suggestion> allSuggestions) {
        if(prefixNode.isEndOfWord()){
            allSuggestions.add(new Suggestion(prefix , prefixNode.getFrequency()));
        }

        for (Character ch : prefixNode.getChildren().keySet()) {
            collectWords(prefixNode.getChildren().get(ch), prefix + ch, allSuggestions);
        }
    }

    private TrieNode findNodeForPrefix(String prefix) {
        TrieNode current = root;
        for(char ch : prefix.toCharArray()){
            current = current.getChildren().get(ch);
            if(current == null){
                return null;
            }
        }
        return current;
    }

}