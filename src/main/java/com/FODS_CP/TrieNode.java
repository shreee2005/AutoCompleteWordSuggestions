package com.FODS_CP;


import lombok.Setter;

import java.util.HashMap;
import java.util.Map;

public class TrieNode {

    // Each child node is mapped by a character.
    // e.g., for the word "cat", the 'c' node will have an 'a' node in its children map.
    private final Map<Character, TrieNode> children = new HashMap<>();

    // This flag is true if this node represents the end of a complete word.
    @Setter
    private boolean isEndOfWord;

    // We store the frequency of the word at its final node.
    @Setter
    private long frequency;

    // Standard Getters and Setters

    public Map<Character, TrieNode> getChildren() {
        return children;
    }

    public boolean isEndOfWord() {
        return isEndOfWord;
    }

    public long getFrequency() {
        return frequency;
    }

}
