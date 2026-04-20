package com.example.audio.vad;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SpeechResilienceTrackerTest {

    @Test
    public void holdsSpeechBrieflyThroughRubbingAfterRecentSpeech() {
        SpeechResilienceTracker tracker = new SpeechResilienceTracker();

        assertTrue(tracker.refine(0L, false, true, 0.03f, 0.05f, 0.01f, 0.04f));
        assertTrue(tracker.refine(160L, false, false, 0.12f, 0.08f, 0.05f, 0.12f));
        assertFalse(tracker.refine(800L, false, false, 0.12f, 0.08f, 0.05f, 0.12f));
    }

    @Test
    public void doesNotInventSpeechWithoutRecentSpeech() {
        SpeechResilienceTracker tracker = new SpeechResilienceTracker();

        assertFalse(tracker.refine(0L, false, false, 0.12f, 0.08f, 0.05f, 0.12f));
    }
}
