package com.example.audio.vad;

import com.example.audio.data.SpeakerRole;

public class VadResult {

    private final long timestampMillis;
    private final boolean speech;
    private final Float confidence;
    private final short[] conditionedFrame;
    private final short[] playbackFrame;
    private final SpeakerRole speakerRole;

    public VadResult(long timestampMillis, boolean speech, Float confidence) {
        this(timestampMillis, speech, confidence, null, null, speech ? SpeakerRole.STUDENT : SpeakerRole.SILENCE);
    }

    public VadResult(
            long timestampMillis,
            boolean speech,
            Float confidence,
            short[] conditionedFrame,
            short[] playbackFrame
    ) {
        this(
                timestampMillis,
                speech,
                confidence,
                conditionedFrame,
                playbackFrame,
                speech ? SpeakerRole.STUDENT : SpeakerRole.SILENCE
        );
    }

    public VadResult(
            long timestampMillis,
            boolean speech,
            Float confidence,
            short[] conditionedFrame,
            short[] playbackFrame,
            SpeakerRole speakerRole
    ) {
        this.timestampMillis = timestampMillis;
        this.speech = speech;
        this.confidence = confidence;
        this.conditionedFrame = conditionedFrame;
        this.playbackFrame = playbackFrame;
        this.speakerRole = speakerRole == null ? SpeakerRole.SILENCE : speakerRole;
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

    public SpeakerRole getSpeakerRole() {
        return speakerRole;
    }
}
