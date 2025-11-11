package com.FODS_CP.Controller;

import com.FODS_CP.data.FrequencyAwareTrie;
import com.FODS_CP.service.Suggestion;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
public class SpellingController {

    private final FrequencyAwareTrie trie;

    @Autowired
    public SpellingController(FrequencyAwareTrie trie) {
        this.trie = trie;
    }

    @GetMapping("/spellcheck")
    public ResponseEntity<List<Suggestion>> spellcheck(@RequestParam("word") String word) {
        if (word == null || word.isBlank()) return ResponseEntity.badRequest().build();
        List<Suggestion> fuzzy = trie.getNearbyByFuzzy(word, 5);
        return ResponseEntity.ok(fuzzy);
    }
}
