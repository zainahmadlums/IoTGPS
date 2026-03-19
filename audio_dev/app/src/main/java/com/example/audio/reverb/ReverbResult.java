package com.example.audio.reverb;

public class ReverbResult {

    public enum Level {
        LOW,
        MEDIUM,
        HIGH
    }

    private final long timestampMillis;
    private final Level level;
    private final float score;

    public ReverbResult(long timestampMillis, Level level, float score) {
        this.timestampMillis = timestampMillis;
        this.level = level;
        this.score = score;
    }

    public long getTimestampMillis() {
        return timestampMillis;
    }

    public Level getLevel() {
        return level;
    }

    public float getReverbScore() {
        return score;
    }
}
