package com.FODS_CP.data;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

@Component
public class FrequencyCsvLoader implements CommandLineRunner {

    private final ResourceLoader resourceLoader;
    private final WordFrequencyService wordFrequencyService;
    private final FrequencyAwareTrie trie; // <-- ADD THIS

    // Modify the constructor to accept the new service
    public FrequencyCsvLoader(ResourceLoader resourceLoader,
                              WordFrequencyService wordFrequencyService,
                              FrequencyAwareTrie trie) { // <-- ADD THIS
        this.resourceLoader = resourceLoader;
        this.wordFrequencyService = wordFrequencyService;
        this.trie = trie;
    }

    @Override
    public void run(String... args) throws Exception {
        // --- This first part is the same as before ---
        System.out.println("Starting frequency CSV loading process...");
        Map<String, Long> frequencies = new HashMap<>();
        Resource resource = resourceLoader.getResource("classpath:word_frequencies.csv");
        CSVFormat csvFormat = CSVFormat.DEFAULT.builder()
                .setHeader("word", "frequency")
                .setSkipHeaderRecord(true)
                .build();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8));
             CSVParser csvParser = new CSVParser(reader, csvFormat)) {
            for (CSVRecord csvRecord : csvParser) {
                String word = csvRecord.get("word");
                try {
                    long frequency = Long.parseLong(csvRecord.get("frequency"));
                    frequencies.put(word, frequency);
                } catch (NumberFormatException e) {
                    System.err.println("Could not parse frequency for word: " + word);
                }
            }
        }
        wordFrequencyService.setWordFrequencies(frequencies);
        System.out.println("Frequency CSV loading process finished.");

        // --- ADD THIS NEW SECTION TO BUILD THE TRIE ---
        System.out.println("Building the Trie data structure...");
        for (Map.Entry<String, Long> entry : frequencies.entrySet()) {
            trie.insert(entry.getKey(), entry.getValue());
        }
        System.out.println("Trie has been built successfully.");
    }

    public void loadCsvAndPopulateTrie() {

    }
}