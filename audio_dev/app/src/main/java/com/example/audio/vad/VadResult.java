package com.example.audio.vad;

public class VadResult {

    private final long timestampMillis;
    private final boolean speech;
    private final Float confidence;
    private final short[] conditionedFrame;
    private final short[] playbackFrame;

    public VadResult(long timestampMillis, boolean speech, Float confidence) {
        this(timestampMillis, speech, confidence, null, null);
    }

    public VadResult(
            long timestampMillis,
            boolean speech,
            Float confidence,
            short[] conditionedFrame,
            short[] playbackFrame
    ) {
        this.timestampMillis = timestampMillis;
        this.speech = speech;
        this.confidence = confidence;
        this.conditionedFrame = conditionedFrame;
        this.playbackFrame = playbackFrame;
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

    public short[] getConditionedFrame() {
        return conditionedFrame;
    }

    public short[] getPlaybackFrame() {
        return playbackFrame;
    }
}
