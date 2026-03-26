package com.example.audio.features;

import com.example.audio.testsupport.SyntheticAudioFactory;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class FeatureCalculatorsTest {

    @Test
    public void rmsCalculatorReturnsZeroForSilence() {
        RmsCalculator calculator = new RmsCalculator();

        float rms = calculator.extract(SyntheticAudioFactory.currentConstantFrame(0));

        assertEquals(0.0f, rms, 0.0001f);
    }

    @Test
    public void rmsCalculatorNormalizesAmplitude() {
        RmsCalculator calculator = new RmsCalculator();

        float rms = calculator.extract(SyntheticAudioFactory.currentConstantFrame(16384));

        assertTrue(rms > 0.49f && rms < 0.51f);
    }

    @Test
    public void zcrCalculatorDetectsFrequentSignChanges() {
        ZcrCalculator calculator = new ZcrCalculator();

        float zcr = calculator.extract(SyntheticAudioFactory.currentAlternatingFrame(12000));

        assertTrue(zcr > 0.95f);
    }

    @Test
    public void spectralFluxCalculatorDetectsFrameChange() {
        SpectralFluxCalculator calculator = new SpectralFluxCalculator();
        calculator.extract(SyntheticAudioFactory.currentConstantFrame(1000));

        float flux = calculator.extract(SyntheticAudioFactory.currentConstantFrame(12000));

        assertTrue(flux > 0.2f);
    }
}
