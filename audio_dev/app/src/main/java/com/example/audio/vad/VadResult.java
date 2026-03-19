package com.example.audio.vad;

public class VadResult {

    private final long timestampMillis;
    private final boolean speech;
    private final Float confidence;

    public VadResult(long timestampMillis, boolean speech, Float confidence) {
        this.timestampMillis = timestampMillis;
        this.speech = speech;
        this.confidence = confidence;
    }

    public long getTimestampMillis() {
        return timestampMillis;
    }

    public boolean isSpeech() {
        return speech;
    }

    public Float getConfidence() {
        return confidence;
    }
}
