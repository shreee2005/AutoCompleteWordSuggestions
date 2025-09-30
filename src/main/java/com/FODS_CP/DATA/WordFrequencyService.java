package com.FODS_CP.DATA;
import org.springframework.stereotype.Service;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class WordFrequencyService {

    private final Map<String, Long> wordFrequencies = new ConcurrentHashMap<>();

    public void setWordFrequencies(Map<String, Long> frequencies) {
        this.wordFrequencies.clear();
        this.wordFrequencies.putAll(frequencies);
        System.out.println("Successfully loaded " + this.wordFrequencies.size() + " word frequencies from CSV.");
    }

    public Map<String, Long> getWordFrequencies() {
        return Collections.unmodifiableMap(wordFrequencies);
    }
}
