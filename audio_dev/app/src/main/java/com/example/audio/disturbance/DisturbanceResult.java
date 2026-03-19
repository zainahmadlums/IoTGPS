package com.example.audio.disturbance;

public class DisturbanceResult {

    private final long timestampMillis;
    private final boolean disturbance;
    private final float severity;

    public DisturbanceResult(long timestampMillis, boolean disturbance, float severity) {
        this.timestampMillis = timestampMillis;
        this.disturbance = disturbance;
        this.severity = severity;
    }

    public long getTimestampMillis() {
        return timestampMillis;
    }

    public boolean isDisturbanceDetected() {
        return disturbance;
    }

    public float getSeverity() {
        return severity;
    }
}
