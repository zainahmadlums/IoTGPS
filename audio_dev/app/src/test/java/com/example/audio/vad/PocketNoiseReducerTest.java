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
        short[] playbackFrame = result.getPlaybackFrame();

        assertTrue(result.getConditionedRms() < result.getRawRms());
        assertTrue(result.getRubbingScore() > result.getSpeechScore());
        assertTrue(result.shouldRejectForVad());
        assertTrue(rms(conditionedFrame) < (rms(constantFrame) * 0.35f));
        assertTrue(rms(playbackFrame) < (rms(constantFrame) * 0.50f));
    }

    @Test
    public void preservesAlternatingSpeechLikeEnergy() {
        PocketNoiseReducer reducer = new PocketNoiseReducer();
        short[] alternatingFrame = SyntheticAudioFactory.currentAlternatingFrame(5000);
        PocketNoiseReducer.Result result = reducer.condition(alternatingFrame, 0.05f, 0.20f);
        short[] conditionedFrame = result.getConditionedFrame();
        short[] playbackFrame = result.getPlaybackFrame();

        assertTrue(rms(conditionedFrame) > (rms(alternatingFrame) * 0.55f));
        assertTrue(rms(playbackFrame) > (rms(alternatingFrame) * 0.65f));
        assertTrue(result.getSpeechScore() > 0.20f);
        assertTrue(!result.shouldRejectForVad());
    }

    @Test
    public void keepsSpeechDominantEnergyWhenRubbingIsMixedIn() {
        PocketNoiseReducer reducer = new PocketNoiseReducer();
        short[] speechFrame = SyntheticAudioFactory.currentSineFrame(7000, 180.0f);
        short[] rubbingFrame = SyntheticAudioFactory.currentConstantFrame(5000);
        short[] mixedFrame = SyntheticAudioFactory.mixedFrame(speechFrame, rubbingFrame);

        PocketNoiseReducer.Result result = reducer.condition(mixedFrame, 0.05f, 0.08f);
        short[] playbackFrame = result.getPlaybackFrame();

        assertTrue(result.getConditionedRms() > 0.02f);
        assertTrue(result.getRubbingScore() < 0.80f);
        assertTrue(result.getSpeechScore() > 0.20f);
        assertTrue(rms(playbackFrame) > (rms(speechFrame) * 0.40f));
        assertTrue(!result.shouldRejectForVad());
    }

    @Test
    public void flagsLowPassedOccludedFrameAsUnreliable() {
        PocketNoiseReducer reducer = new PocketNoiseReducer();
        short[] lowPassedSpeech = SyntheticAudioFactory.currentSineFrame(4500, 120.0f);

        PocketNoiseReducer.Result result = reducer.condition(lowPassedSpeech, 0.01f, 0.04f);

        assertTrue(result.getOcclusionScore() > 0.20f);
        assertTrue(result.getOcclusionScore() > result.getRubbingScore() * 0.40f);
    }

    private float rms(short[] frame) {
        double energy = 0.0d;
        for (short sample : frame) {
            energy += (double) sample * sample;
        }
        return (float) Math.sqrt(energy / frame.length);
    }
}
