package com.FODS_CP.service;

/**
 * Represents a single autocomplete suggestion result.
 * Stores the text, frequency, recency, and computed score.
 */
public class Suggestion {

    private String text;
    private long frequency;
    private long lastUsedEpochMillis;
    private double score;

    public Suggestion(String text, long frequency) {
        this.text = text;
        this.frequency = frequency;
        this.lastUsedEpochMillis = 0L;
        this.score = 0.0;
    }

    public Suggestion(String text, long frequency, long lastUsedEpochMillis) {
        this.text = text;
        this.frequency = frequency;
        this.lastUsedEpochMillis = lastUsedEpochMillis;
        this.score = 0.0;
    }

    public Suggestion() {
        this.text = "";
        this.frequency = 0L;
        this.lastUsedEpochMillis = 0L;
        this.score = 0.0;
    }

    // --- Getters and Setters ---

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public long getFrequency() {
        return frequency;
    }

    public void setFrequency(long frequency) {
        this.frequency = frequency;
    }

    public long getLastUsedEpochMillis() {
        return lastUsedEpochMillis;
    }

    public void setLastUsedEpochMillis(long lastUsedEpochMillis) {
        this.lastUsedEpochMillis = lastUsedEpochMillis;
    }

    public double getScore() {
        return score;
    }

    public void setScore(double score) {
        this.score = score;
    }

    // --- Utility methods ---

    @Override
    public String toString() {
        return "Suggestion{" +
                "text='" + text + '\'' +
                ", frequency=" + frequency +
                ", lastUsedEpochMillis=" + lastUsedEpochMillis +
                ", score=" + score +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Suggestion)) return false;
        Suggestion that = (Suggestion) o;
        return text != null && text.equalsIgnoreCase(that.text);
    }

    @Override
    public int hashCode() {
        return text == null ? 0 : text.toLowerCase().hashCode();
    }
}
