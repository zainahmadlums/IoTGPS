package com.example.audio.disturbance;

import com.example.audio.features.RmsCalculator;
import com.example.audio.features.SpectralFluxCalculator;
import com.example.audio.features.ZcrCalculator;
import com.example.audio.util.MathUtils;
import com.example.audio.vad.VadResult;

public class EnergySpikeDetector implements DisturbanceDetector {

    private static final float BASELINE_ALPHA = 0.08f;
    private static final float MIN_SPIKE_RMS = 0.10f;
    private static final float RMS_SPIKE_FACTOR = 2.4f;
    private static final float MIN_FLUX = 0.035f;
    private static final int MAX_SPIKE_FRAMES = 3;

    private final RmsCalculator rmsCalculator = new RmsCalculator();
    private final ZcrCalculator zcrCalculator = new ZcrCalculator();
    private final SpectralFluxCalculator spectralFluxCalculator = new SpectralFluxCalculator();

    private float runningBaselineRms = 0.02f;
    private int consecutiveSpikeFrames;
    private Boolean previousSpeech;

    @Override
    public DisturbanceResult analyze(short[] frame, long timestampMillis, VadResult vadResult) {
        float rms = rmsCalculator.extract(frame);
        float zcr = zcrCalculator.extract(frame);
        float spectralFlux = spectralFluxCalculator.extract(frame);

        boolean speech = vadResult != null && vadResult.isSpeech();
        boolean speechUnstable = previousSpeech != null && previousSpeech != speech;
        boolean highEnergySpike = rms >= MIN_SPIKE_RMS
                && rms >= runningBaselineRms * RMS_SPIKE_FACTOR;

        if (highEnergySpike) {
            consecutiveSpikeFrames++;
        } else {
            consecutiveSpikeFrames = 0;
        }

        boolean shortDuration = consecutiveSpikeFrames > 0 && consecutiveSpikeFrames <= MAX_SPIKE_FRAMES;
        boolean nonSpeechOrUnstable = !speech || speechUnstable;
        boolean abruptChange = spectralFlux >= MIN_FLUX || zcr >= 0.18f;

        float spikeRatio = runningBaselineRms > 0.0f ? rms / runningBaselineRms : 0.0f;
        float severity = MathUtils.clamp(
                ((spikeRatio - 1.0f) / 3.0f) + spectralFlux + (zcr * 0.35f),
                0.0f,
                1.0f
        );

        boolean isDisturbance = highEnergySpike
                && shortDuration
                && nonSpeechOrUnstable
                && abruptChange;

        previousSpeech = speech;
        updateBaseline(rms, speech, isDisturbance);
        return new DisturbanceResult(timestampMillis, isDisturbance, severity);
    }

    private void updateBaseline(float rms, boolean speech, boolean disturbance) {
        if (disturbance) {
            return;
        }

        float alpha = speech ? BASELINE_ALPHA * 0.5f : BASELINE_ALPHA;
        runningBaselineRms += alpha * (rms - runningBaselineRms);
        runningBaselineRms = Math.max(0.01f, runningBaselineRms);
    }
}
