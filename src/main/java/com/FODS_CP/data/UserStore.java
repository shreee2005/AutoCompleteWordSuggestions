package com.FODS_CP.data;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple file-backed user personalization store.
 * Writes periodically and at shutdown to a JSON file.
 */
@Component
public class UserStore {
    private final ConcurrentHashMap<String, ConcurrentHashMap<String,Integer>> store = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();
    private final File file;

    public UserStore() {
        try {
            Path p = Path.of("user_personalization.json").toAbsolutePath();
            file = p.toFile();
            if (file.exists()) {
                Map<String, Map<String,Integer>> loaded = mapper.readValue(file, new TypeReference<>(){});
                loaded.forEach((k,v) -> store.put(k, new ConcurrentHashMap<>(v)));
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to load user store", e);
        }
        // add shutdown hook to persist
        Runtime.getRuntime().addShutdownHook(new Thread(this::save));
    }

    public Map<String,Integer> getUser(String userId) {
        return store.computeIfAbsent(userId, k -> new ConcurrentHashMap<>());
    }

    public void increment(String userId, String key) {
        getUser(userId).merge(key.toLowerCase(), 1, Integer::sum);
    }

    public void save() {
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(file, store);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
