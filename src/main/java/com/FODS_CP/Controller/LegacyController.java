package com.FODS_CP.Controller;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.FODS_CP.Controller.AutocompleteController;

/**
 * Small compatibility controller that exposes /autocomplete at root (no /api prefix)
 * and delegates to the real AutocompleteController.suggest(...) method.
 */
@RestController
public class LegacyController {

    private final AutocompleteController autocompleteController;

    @Autowired
    public LegacyController(AutocompleteController autocompleteController) {
        this.autocompleteController = autocompleteController;
    }

    @GetMapping("/autocomplete")
    public ResponseEntity<?> legacyAutocomplete(
            @RequestParam(value = "prefix", required = false) String prefix,
            @RequestParam(value = "context", required = false) String context,
            @RequestParam(value = "limit", defaultValue = "6") int limit,
            @RequestParam(value = "userId", required = false) String userId
    ) {
        // AutocompleteController expects param name "q" for the query.
        // pass prefix as q and delegate.
        return autocompleteController.suggest(prefix, context, limit, userId);
    }
}
