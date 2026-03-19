package com.example.audio.reverb;

import com.example.audio.testsupport.SyntheticAudioFactory;
import com.example.audio.vad.VadResult;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class EnergyDecayReverbEstimatorTest {

    @Test
    public void classifiesQuickDecayAsLowReverb() {
        EnergyDecayReverbEstimator estimator = new EnergyDecayReverbEstimator();
        long timestamp = primeSpeech(estimator);

        ReverbResult result = estimator.estimate(
                SyntheticAudioFactory.constantFrame(320, 300),
                timestamp,
                new VadResult(timestamp, false, null)
        );

        assertEquals(ReverbResult.Level.LOW, result.getLevel());
        assertTrue(result.getReverbScore() < 0.28f);
    }

    @Test
    public void classifiesSustainedDecayAsHighReverbLikeTail() {
        EnergyDecayReverbEstimator estimator = new EnergyDecayReverbEstimator();
        long timestamp = primeSpeech(estimator);
        ReverbResult result = null;
        int[] tailAmplitudes = new int[]{12000, 10500, 9000, 7600};

        for (int tailAmplitude : tailAmplitudes) {
            result = estimator.estimate(
                    SyntheticAudioFactory.constantFrame(320, tailAmplitude),
                    timestamp,
                    new VadResult(timestamp, false, null)
            );
            timestamp += 20L;
        }

        assertTrue(result != null);
        assertTrue(result.getLevel() == ReverbResult.Level.MEDIUM
                || result.getLevel() == ReverbResult.Level.HIGH);
        assertTrue(result.getReverbScore() >= 0.28f);
    }

    private long primeSpeech(EnergyDecayReverbEstimator estimator) {
        long timestamp = 0L;
        for (int index = 0; index < 3; index++) {
            estimator.estimate(
                    SyntheticAudioFactory.constantFrame(320, 20000),
                    timestamp,
                    new VadResult(timestamp, true, null)
            );
            timestamp += 20L;
        }
        return timestamp;
    }
}
