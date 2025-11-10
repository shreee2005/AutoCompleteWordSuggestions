package com.FODS_CP.service;

import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class CategoryService {

    private static final Map<String, String> KEYWORDS = Map.ofEntries(
            Map.entry("java", "Programming"),
            Map.entry("spring", "Programming"),
            Map.entry("football", "Sports"),
            Map.entry("cricket", "Sports"),
            Map.entry("music", "Entertainment"),
            Map.entry("movie", "Entertainment")
    );

    public String getCategory(String word) {
        for (var e : KEYWORDS.entrySet()) {
            if (word.toLowerCase().contains(e.getKey())) return e.getValue();
        }
        return "General";
    }

    public Map<String, List<String>> groupByCategory(List<String> words) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (String w : words) {
            out.computeIfAbsent(getCategory(w), k -> new ArrayList<>()).add(w);
        }
        return out;
    }
}
