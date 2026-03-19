package com.example.audio.data;

import com.example.audio.reverb.ReverbResult;

public class SpeechEvent {

    private final long timestampMillis;
    private final boolean speech;
    private final boolean disturbance;
    private final ReverbResult.Level reverbLevel;

    public SpeechEvent(
            long timestampMillis,
            boolean speech,
            boolean disturbance,
            ReverbResult.Level reverbLevel
    ) {
        this.timestampMillis = timestampMillis;
        this.speech = speech;
        this.disturbance = disturbance;
        this.reverbLevel = reverbLevel;
    }

    public long getTimestampMillis() {
        return timestampMillis;
    }

    public boolean isSpeech() {
        return speech;
    }

    public boolean isDisturbance() {
        return disturbance;
    }

    public ReverbResult.Level getReverbLevel() {
        return reverbLevel;
    }
}
