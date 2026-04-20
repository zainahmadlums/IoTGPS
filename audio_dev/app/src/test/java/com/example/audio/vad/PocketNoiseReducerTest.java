package com.example.audio.vad;

import com.example.audio.testsupport.SyntheticAudioFactory;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class PocketNoiseReducerTest {

    @Test
    public void attenuatesRepeatedConstantContactNoise() {
        PocketNoiseReducer reducer = new PocketNoiseReducer();
        short[] constantFrame = SyntheticAudioFactory.currentConstantFrame(9000);

        reducer.condition(constantFrame, 0.01f, 0.0f);
        PocketNoiseReducer.Result result = reducer.condition(constantFrame, 0.01f, 0.0f);
        short[] conditionedFrame = result.getConditionedFrame();

        assertTrue(result.getConditionedRms() < result.getRawRms());
        assertTrue(result.getRubbingScore() > result.getSpeechScore());
    }

    @Test
    public void preservesAlternatingSpeechLikeEnergy() {
        PocketNoiseReducer reducer = new PocketNoiseReducer();
        short[] alternatingFrame = SyntheticAudioFactory.currentAlternatingFrame(5000);
        PocketNoiseReducer.Result result = reducer.condition(alternatingFrame, 0.05f, 0.20f);
        short[] conditionedFrame = result.getConditionedFrame();

        assertTrue(rms(conditionedFrame) > (rms(alternatingFrame) * 0.55f));
        assertTrue(result.getSpeechScore() > 0.20f);
    }

    @Test
    public void keepsSpeechDominantEnergyWhenRubbingIsMixedIn() {
        PocketNoiseReducer reducer = new PocketNoiseReducer();
        short[] speechFrame = SyntheticAudioFactory.currentSineFrame(7000, 180.0f);
        short[] rubbingFrame = SyntheticAudioFactory.currentConstantFrame(5000);
        short[] mixedFrame = SyntheticAudioFactory.mixedFrame(speechFrame, rubbingFrame);

        PocketNoiseReducer.Result result = reducer.condition(mixedFrame, 0.05f, 0.08f);

        assertTrue(result.getConditionedRms() > 0.02f);
        assertTrue(result.getRubbingScore() < 0.80f);
        assertTrue(result.getSpeechScore() > 0.20f);
    }

    private float rms(short[] frame) {
        double energy = 0.0d;
        for (short sample : frame) {
            energy += (double) sample * sample;
        }
        return (float) Math.sqrt(energy / frame.length);
    }
}
