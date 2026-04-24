package com.example.audio.vad;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SpeechResilienceTrackerTest {

    @Test
    public void entersSpeechWhenConditionedPathRecoversSpeech() {
        SpeechResilienceTracker tracker = new SpeechResilienceTracker();
        PocketNoiseReducer.Result recoveredSpeech = new PocketNoiseReducer.Result(
                new short[0],
                new short[0],
                0.05f,
                0.04f,
                0.06f,
                0.10f,
                0.42f,
                0.58f,
                0.76f,
                0.38f,
                0.88f
        );

        assertFalse(tracker.refine(0L, false, true, recoveredSpeech).isSpeech());
        assertTrue(tracker.refine(32L, false, true, recoveredSpeech).isSpeech());
    }

    @Test
    public void entersSpeechOnModerateRecoveredVoicing() {
        SpeechResilienceTracker tracker = new SpeechResilienceTracker();
        PocketNoiseReducer.Result recoveredSpeech = new PocketNoiseReducer.Result(
                new short[0],
                new short[0],
                0.04f,
                0.03f,
                0.05f,
                0.11f,
                0.48f,
                0.52f,
                0.68f,
                0.52f,
                0.56f
        );

        assertFalse(tracker.refine(0L, false, true, recoveredSpeech).isSpeech());
        assertTrue(tracker.refine(32L, false, true, recoveredSpeech).isSpeech());
    }

    @Test
    public void holdsSpeechBrieflyThroughRubbingAfterRecentSpeech() {
        SpeechResilienceTracker tracker = new SpeechResilienceTracker();
        PocketNoiseReducer.Result speechFrame = new PocketNoiseReducer.Result(
                new short[0],
                new short[0],
                0.05f,
                0.05f,
                0.03f,
                0.07f,
                0.35f,
                0.65f,
                0.60f,
                0.28f,
                0.62f
        );
        PocketNoiseReducer.Result rubbingFrame = new PocketNoiseReducer.Result(
                new short[0],
                new short[0],
                0.10f,
                0.035f,
                0.08f,
                0.16f,
                0.72f,
                0.28f,
                0.22f,
                0.70f,
                0.24f
        );

        tracker.refine(0L, true, true, speechFrame);
        tracker.refine(32L, true, true, speechFrame);
        assertTrue(tracker.refine(160L, false, false, rubbingFrame).isSpeech());
        assertTrue(tracker.refine(320L, false, false, rubbingFrame).isSpeech());
        assertFalse(tracker.refine(800L, false, false, rubbingFrame).isSpeech());
    }

    @Test
    public void exitsAfterSustainedSilence() {
        SpeechResilienceTracker tracker = new SpeechResilienceTracker();
        PocketNoiseReducer.Result speechFrame = new PocketNoiseReducer.Result(
                new short[0],
                new short[0],
                0.05f,
                0.05f,
                0.03f,
                0.07f,
                0.35f,
                0.65f,
                0.60f,
                0.28f,
                0.62f
        );
        PocketNoiseReducer.Result silenceFrame = new PocketNoiseReducer.Result(
                new short[0],
                new short[0],
                0.01f,
                0.01f,
                0.01f,
                0.02f,
                0.50f,
                0.50f,
                0.08f,
                0.12f,
                0.08f
        );

        tracker.refine(0L, true, true, speechFrame);
        tracker.refine(32L, true, true, speechFrame);
        assertTrue(tracker.refine(64L, false, false, silenceFrame).isSpeech());
        assertTrue(tracker.refine(96L, false, false, silenceFrame).isSpeech());
        assertFalse(tracker.refine(128L, false, false, silenceFrame).isSpeech());
    }
}
