package com.FODS_CP.Controller;

import com.FODS_CP.DATA.FrequencyAwareTrie;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;

@RestController
public class AutocompleteController {
    private final FrequencyAwareTrie trie;

    public AutocompleteController(FrequencyAwareTrie trie) {
        this.trie = trie;
    }
    @GetMapping("/autocomplete")
    public List<String> autocomplete(@RequestParam(defaultValue = "") String prefix) {
        if(prefix.isBlank()){
            return Collections.emptyList();
        }
        return trie.getSuggestions(prefix.toLowerCase());
    }
}
