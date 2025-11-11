package com.FODS_CP.service;

public class Suggestion implements Comparable<Suggestion> {
    private String text;
    private long frequency;
    private double score;
    private long lastUsedEpochMillis; // 0 if unknown

    public Suggestion() {}

    public Suggestion(String text, long frequency) {
        this.text = text;
        this.frequency = frequency;
        this.score = 0.0;
        this.lastUsedEpochMillis = 0L;
    }

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }

    public long getFrequency() { return frequency; }
    public void setFrequency(long frequency) { this.frequency = frequency; }

    public double getScore() { return score; }
    public void setScore(double score) { this.score = score; }

    public long getLastUsedEpochMillis() { return lastUsedEpochMillis; }
    public void setLastUsedEpochMillis(long lastUsedEpochMillis) {
        this.lastUsedEpochMillis = lastUsedEpochMillis;
    }

    @Override
    public int compareTo(Suggestion o) {
        return Double.compare(o.score, this.score); // descending
    }

    @Override
    public String toString() {
        return "Suggestion{" +
                "text='" + text + '\'' +
                ", freq=" + frequency +
                ", score=" + score +
                ", lastUsed=" + lastUsedEpochMillis +
                '}';
    }
}
