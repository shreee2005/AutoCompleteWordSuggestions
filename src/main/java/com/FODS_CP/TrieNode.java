package com.FODS_CP;


import lombok.Setter;

import java.util.HashMap;
import java.util.Map;

public class TrieNode {

    // Each child node is mapped by a character.
    // e.g., for the word "cat", the 'c' node will have an 'a' node in its children map.
    private final Map<Character, TrieNode> children = new HashMap<>();

    // This flag is true if this node represents the end of a complete word.
    private boolean isEndOfWord;

    private long frequency;

    public void setEndOfWord(boolean endOfWord) {
        // This setter assigns the boolean value.
        isEndOfWord = endOfWord;
    }

    public void setFrequency(long frequency) {
        // This is the most critical setter.
        // It MUST assign the passed-in frequency to the class field.
        this.frequency = frequency;
    }
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
