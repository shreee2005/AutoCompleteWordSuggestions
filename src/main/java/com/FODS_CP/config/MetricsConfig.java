package com.FODS_CP.config;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Autowired;

@Configuration
public class MetricsConfig {
    @Autowired
    public MetricsConfig(MeterRegistry registry) {
        // you can add commonTags here if needed
        registry.counter("autocomplete.startups", "app", "fodscp");
    }
}
