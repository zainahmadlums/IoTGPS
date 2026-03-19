package com.example.audio.reverb;

import com.example.audio.features.RmsCalculator;
import com.example.audio.util.MathUtils;
import com.example.audio.vad.VadResult;

public class EnergyDecayReverbEstimator implements ReverbEstimator {

    private static final int MAX_TAIL_FRAMES = 10;
    private static final float MIN_SPEECH_RMS = 0.05f;
    private static final float MIN_TAIL_RATIO = 0.03f;
    private static final float MEDIUM_SCORE_THRESHOLD = 0.28f;
    private static final float HIGH_SCORE_THRESHOLD = 0.48f;

    private final RmsCalculator rmsCalculator = new RmsCalculator();

    private boolean previousSpeech;
    private float speechPeakRms;
    private int trailingFrames;
    private float firstTailRatio;
    private float lastTailRatio;
    private float accumulatedTailRatio;
    private int monotonicDecaySteps;

    @Override
    public ReverbResult estimate(short[] frame, long timestampMillis, VadResult vadResult) {
        float rms = rmsCalculator.extract(frame);
        boolean speech = vadResult != null && vadResult.isSpeech();

        if (speech) {
            speechPeakRms = Math.max(speechPeakRms * 0.85f, rms);
            previousSpeech = true;
            resetTailState();
            return new ReverbResult(timestampMillis, ReverbResult.Level.LOW, 0.0f);
        }

        if (previousSpeech && speechPeakRms >= MIN_SPEECH_RMS) {
            float tailRatio = rms / Math.max(speechPeakRms, 0.0001f);
            if (tailRatio < MIN_TAIL_RATIO && trailingFrames > 0) {
                previousSpeech = false;
                resetTailState();
                speechPeakRms *= 0.65f;
                return new ReverbResult(timestampMillis, ReverbResult.Level.LOW, 0.0f);
            }

            trailingFrames++;
            if (trailingFrames == 1) {
                firstTailRatio = tailRatio;
            } else if (tailRatio <= lastTailRatio + 0.015f) {
                monotonicDecaySteps++;
            }
            lastTailRatio = tailRatio;
            accumulatedTailRatio += tailRatio;

            float averageTailRatio = accumulatedTailRatio / trailingFrames;
            float endRatio = lastTailRatio;
            float monotonicity = trailingFrames <= 1
                    ? 1.0f
                    : (float) monotonicDecaySteps / (trailingFrames - 1);
            float evidence = Math.min(1.0f, trailingFrames / 4.0f);
            float score = MathUtils.clamp(
                    ((averageTailRatio * 0.45f)
                            + (endRatio * 0.35f)
                            + (monotonicity * 0.20f)) * evidence,
                    0.0f,
                    1.0f
            );

            ReverbResult.Level level = toLevel(score);

            if (trailingFrames >= MAX_TAIL_FRAMES) {
                previousSpeech = false;
                resetTailState();
                speechPeakRms *= 0.7f;
            }

            return new ReverbResult(timestampMillis, level, score);
        }

        previousSpeech = false;
        resetTailState();
        speechPeakRms *= 0.95f;
        return new ReverbResult(timestampMillis, ReverbResult.Level.LOW, 0.0f);
    }

    private ReverbResult.Level toLevel(float score) {
        if (score >= HIGH_SCORE_THRESHOLD) {
            return ReverbResult.Level.HIGH;
        }
        if (score >= MEDIUM_SCORE_THRESHOLD) {
            return ReverbResult.Level.MEDIUM;
        }
        return ReverbResult.Level.LOW;
    }

    private void resetTailState() {
        trailingFrames = 0;
        firstTailRatio = 0.0f;
        lastTailRatio = 0.0f;
        accumulatedTailRatio = 0.0f;
        monotonicDecaySteps = 0;
    }
}
