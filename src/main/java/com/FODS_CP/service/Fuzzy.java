package com.FODS_CP.service;

import org.apache.commons.text.similarity.LevenshteinDistance;

public final class Fuzzy {
    private static final LevenshteinDistance LD = new LevenshteinDistance(5); // allow up to 5 for apply()

    private Fuzzy() {}

    /**
     * Returns a similarity score in [0.0, 1.0]. 1.0 = exact match.
     * Uses normalized (1 - distance / maxLen) where maxLen = max(len(a),len(b)).
     */
    public static double similarity(String a, String b) {
        if (a == null || b == null) return 0.0;
        if (a.equalsIgnoreCase(b)) return 1.0;
        a = a.trim().toLowerCase();
        b = b.trim().toLowerCase();
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        int dist = LD.apply(a, b);
        // if LevenshteinDistance returns -1 when over threshold, convert to a large distance
        if (dist < 0) dist = Math.max(a.length(), b.length());
        double max = Math.max(a.length(), b.length());
        if (max == 0) return 0.0;
        double sim = 1.0 - (dist / max);
        if (sim < 0) sim = 0.0;
        return sim;
    }
}
