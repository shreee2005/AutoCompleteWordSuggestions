package com.FODS_CP.service;

import org.apache.commons.text.similarity.LevenshteinDistance;

/**
 * Small fuzzy similarity utility.
 * Returns similarity in range [0.0, 1.0], where 1.0 = exact match.
 */
public final class Fuzzy {

    // try to use Apache Commons if present; otherwise we'll use our fallback.
    private static final LevenshteinDistance apache;
    static {
        LevenshteinDistance tmp = null;
        try {
            tmp = new LevenshteinDistance(); // no max distance
        } catch (Throwable ignored) {
            tmp = null;
        }
        apache = tmp;
    }

    private Fuzzy() {}

    /**
     * Compute normalized similarity between two strings.
     * Returns 1.0 for exact match, 0.0 for totally different.
     */
    public static double similarity(String a, String b) {
        if (a == null && b == null) return 1.0;
        if (a == null || b == null) return 0.0;

        a = a.trim().toLowerCase();
        b = b.trim().toLowerCase();

        if (a.equals(b)) return 1.0;
        if (a.isEmpty() || b.isEmpty()) return 0.0;

        // fast prefix check
        if (a.startsWith(b) || b.startsWith(a)) {
            // if one is prefix of other, similarity high depending on length ratio
            int min = Math.min(a.length(), b.length());
            int max = Math.max(a.length(), b.length());
            double base = (double) min / (double) max;
            return Math.min(1.0, base + 0.15); // small boost
        }

        int dist;
        if (apache != null) {
            try {
                dist = apache.apply(a, b);
            } catch (Throwable t) {
                dist = levenshteinFallback(a, b);
            }
        } else {
            dist = levenshteinFallback(a, b);
        }

        int maxLen = Math.max(a.length(), b.length());
        if (maxLen == 0) return 1.0;
        double sim = 1.0 - ((double) dist / (double) maxLen);
        // small prefix boost
        if (a.charAt(0) == b.charAt(0)) sim += 0.02;
        if (sim < 0.0) sim = 0.0;
        if (sim > 1.0) sim = 1.0;
        return sim;
    }

    // simple DP Levenshtein implementation (fallback)
    private static int levenshteinFallback(String a, String b) {
        final int n = a.length();
        final int m = b.length();
        if (n == 0) return m;
        if (m == 0) return n;

        int[] prev = new int[m + 1];
        int[] cur = new int[m + 1];

        for (int j = 0; j <= m; j++) prev[j] = j;

        for (int i = 1; i <= n; i++) {
            cur[0] = i;
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= m; j++) {
                int cost = (ca == b.charAt(j - 1)) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = cur;
            cur = tmp;
        }
        return prev[m];
    }
}
