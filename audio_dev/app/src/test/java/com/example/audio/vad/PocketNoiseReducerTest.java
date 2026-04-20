package com.example.audio.vad;

import com.example.audio.testsupport.SyntheticAudioFactory;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class PocketNoiseReducerTest {

    @Test
    public void attenuatesRepeatedConstantContactNoise() {
        PocketNoiseReducer reducer = new PocketNoiseReducer();
        short[] constantFrame = SyntheticAudioFactory.currentConstantFrame(9000);

        reducer.condition(constantFrame);
        short[] conditionedFrame = reducer.condition(constantFrame);

        assertTrue(rms(conditionedFrame) < (rms(constantFrame) * 0.35f));
    }

    @Test
    public void preservesAlternatingSpeechLikeEnergy() {
        PocketNoiseReducer reducer = new PocketNoiseReducer();
        short[] alternatingFrame = SyntheticAudioFactory.currentAlternatingFrame(5000);
        short[] conditionedFrame = reducer.condition(alternatingFrame);

        assertTrue(rms(conditionedFrame) > (rms(alternatingFrame) * 0.55f));
    }

    private float rms(short[] frame) {
        double energy = 0.0d;
        for (short sample : frame) {
            energy += (double) sample * sample;
        }
        return (float) Math.sqrt(energy / frame.length);
    }
}
