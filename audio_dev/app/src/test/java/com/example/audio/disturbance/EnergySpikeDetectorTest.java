package com.example.audio.disturbance;

import com.example.audio.testsupport.SyntheticAudioFactory;
import com.example.audio.vad.VadResult;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class EnergySpikeDetectorTest {

    @Test
    public void detectsShortNonSpeechSpikeAsDisturbance() {
        EnergySpikeDetector detector = new EnergySpikeDetector();
        long timestamp = 0L;

        for (int index = 0; index < 5; index++) {
            DisturbanceResult quietResult = detector.analyze(
                    SyntheticAudioFactory.constantFrame(320, 200),
                    timestamp,
                    new VadResult(timestamp, false, null)
            );
            assertFalse(quietResult.isDisturbanceDetected());
            timestamp += 20L;
        }

        DisturbanceResult spikeResult = detector.analyze(
                SyntheticAudioFactory.alternatingFrame(320, 22000),
                timestamp,
                new VadResult(timestamp, false, null)
        );

        assertTrue(spikeResult.isDisturbanceDetected());
        assertTrue(spikeResult.getSeverity() > 0.2f);
    }
}
