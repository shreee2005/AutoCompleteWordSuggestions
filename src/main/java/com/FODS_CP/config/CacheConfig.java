package com.FODS_CP.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import com.github.benmanes.caffeine.cache.Cache;

@Configuration
public class CacheConfig {

    // caches prefix -> serialized result (you store Suggestion list)
    @Bean("suggestionCache")
    public Cache<String, Object> suggestionCache() {
        return Caffeine.newBuilder()
                .maximumSize(50_000)
                .expireAfterWrite(Duration.ofMinutes(10))
                .build();
    }
}
