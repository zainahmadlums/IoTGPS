package com.example.audio.vad;

import com.example.audio.util.MathUtils;

final class PocketNoiseReducer {

    static final class Result {
        private final short[] conditionedFrame;
        private final float rawRms;
        private final float conditionedRms;
        private final float spectralFlux;
        private final float zcr;
        private final float lowBandRatio;
        private final float highBandRatio;
        private final float voicingScore;
        private final float rubbingScore;
        private final float speechScore;

        Result(
                short[] conditionedFrame,
                float rawRms,
                float conditionedRms,
                float spectralFlux,
                float zcr,
                float lowBandRatio,
                float highBandRatio,
                float voicingScore,
                float rubbingScore,
                float speechScore
        ) {
            this.conditionedFrame = conditionedFrame;
            this.rawRms = rawRms;
            this.conditionedRms = conditionedRms;
            this.spectralFlux = spectralFlux;
            this.zcr = zcr;
            this.lowBandRatio = lowBandRatio;
            this.highBandRatio = highBandRatio;
            this.voicingScore = voicingScore;
            this.rubbingScore = rubbingScore;
            this.speechScore = speechScore;
        }

        short[] getConditionedFrame() {
            return conditionedFrame;
        }

        float getRawRms() {
            return rawRms;
        }

        float getConditionedRms() {
            return conditionedRms;
        }

        float getSpectralFlux() {
            return spectralFlux;
        }

        float getZcr() {
            return zcr;
        }

        float getLowBandRatio() {
            return lowBandRatio;
        }

        float getHighBandRatio() {
            return highBandRatio;
        }

        float getVoicingScore() {
            return voicingScore;
        }

        float getRubbingScore() {
            return rubbingScore;
        }

        float getSpeechScore() {
            return speechScore;
        }
    }

    private static final float LOW_PASS_SMOOTHING = 0.12f;
    private static final float TARGET_RMS = 0.070f;
    private static final float MIN_RMS_FOR_GAIN = 0.010f;
    private static final float MAX_GAIN = 4.0f;
    private static final float GAIN_SMOOTHING_ALPHA = 0.18f;
    private static final float BASE_AMBIENT_FLOOR_MIX = 0.12f;
    private static final float MIN_AMBIENT_FLOOR_MIX = 0.04f;
    private static final float MAX_AMBIENT_FLOOR_MIX = 0.24f;
    private static final float MIN_LOW_BAND_ATTENUATION = 0.18f;
    private static final float MIN_HIGH_BAND_ATTENUATION = 0.72f;
    private static final int MIN_PITCH_LAG = 32;
    private static final int MAX_PITCH_LAG = 160;
    private static final int LAG_STEP = 4;

    private float lowPassState;
    private float smoothedGain = 1.0f;

    Result condition(short[] frame, float spectralFlux, float zcr) {
        if (frame == null) {
            return new Result(new short[0], 0.0f, 0.0f, spectralFlux, zcr, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f);
        }

        short[] conditionedFrame = new short[frame.length];
        if (frame.length == 0) {
            return new Result(conditionedFrame, 0.0f, 0.0f, spectralFlux, zcr, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f);
        }

        float[] lowBand = new float[frame.length];
        float[] highBand = new float[frame.length];
        double rawEnergy = 0.0d;
        double lowEnergy = 0.0d;
        double highEnergy = 0.0d;
        for (int index = 0; index < frame.length; index++) {
            float input = frame[index];
            lowPassState += LOW_PASS_SMOOTHING * (input - lowPassState);
            float lowComponent = lowPassState;
            float highComponent = input - lowComponent;

            lowBand[index] = lowComponent;
            highBand[index] = highComponent;
            rawEnergy += input * input;
            lowEnergy += lowComponent * lowComponent;
            highEnergy += highComponent * highComponent;
        }

        float rawRms = (float) (Math.sqrt(rawEnergy / frame.length) / Short.MAX_VALUE);
        float lowBandRatio = rawEnergy > 0.0d ? (float) (lowEnergy / rawEnergy) : 0.0f;
        float highBandRatio = rawEnergy > 0.0d ? (float) (highEnergy / rawEnergy) : 0.0f;
        float voicingScore = estimateVoicing(frame);
        float fluxScore = MathUtils.clamp(spectralFlux / 0.08f, 0.0f, 1.0f);
        float zcrScore = MathUtils.clamp(zcr / 0.22f, 0.0f, 1.0f);

        float speechScore = MathUtils.clamp(
                (0.48f * voicingScore)
                        + (0.32f * highBandRatio)
                        + (0.12f * (1.0f - Math.min(1.0f, fluxScore * 0.6f)))
                        + (0.08f * (1.0f - Math.min(1.0f, zcrScore * 0.5f))),
                0.0f,
                1.0f
        );
        float rubbingScore = MathUtils.clamp(
                (0.32f * lowBandRatio)
                        + (0.26f * fluxScore)
                        + (0.18f * zcrScore)
                        + (0.24f * (1.0f - voicingScore)),
                0.0f,
                1.0f
        );

        float speechProtection = MathUtils.clamp(
                (0.62f * speechScore)
                        + (0.24f * voicingScore)
                        + (0.14f * highBandRatio),
                0.0f,
                1.0f
        );
        float lowBandAttenuation = MathUtils.clamp(
                1.0f - (0.88f * rubbingScore * (1.0f - (0.82f * speechProtection))),
                MIN_LOW_BAND_ATTENUATION,
                1.0f
        );
        float highBandAttenuation = MathUtils.clamp(
                1.0f - (0.20f * rubbingScore * (1.0f - (0.72f * speechProtection))),
                MIN_HIGH_BAND_ATTENUATION,
                1.0f
        );
        float ambientFloorMix = MathUtils.clamp(
                BASE_AMBIENT_FLOOR_MIX
                        + (0.14f * speechProtection)
                        - (0.16f * rubbingScore),
                MIN_AMBIENT_FLOOR_MIX,
                MAX_AMBIENT_FLOOR_MIX
        );

        double conditionedEnergy = 0.0d;
        float[] blendedFrame = new float[frame.length];
        for (int index = 0; index < frame.length; index++) {
            float suppressed = (lowBand[index] * lowBandAttenuation)
                    + (highBand[index] * highBandAttenuation);
            float blended = (ambientFloorMix * frame[index])
                    + ((1.0f - ambientFloorMix) * suppressed);
            blendedFrame[index] = blended;
            conditionedEnergy += blended * blended;
        }

        float conditionedRmsBeforeGain =
                (float) (Math.sqrt(conditionedEnergy / frame.length) / Short.MAX_VALUE);
        float desiredGain = TARGET_RMS / Math.max(conditionedRmsBeforeGain, MIN_RMS_FOR_GAIN);
        desiredGain = MathUtils.clamp(desiredGain, 1.0f, MAX_GAIN);
        smoothedGain += GAIN_SMOOTHING_ALPHA * (desiredGain - smoothedGain);

        double finalConditionedEnergy = 0.0d;
        for (int index = 0; index < blendedFrame.length; index++) {
            short sample = saturate(Math.round(blendedFrame[index] * smoothedGain));
            conditionedFrame[index] = sample;
            finalConditionedEnergy += sample * (double) sample;
        }

        float conditionedRms =
                (float) (Math.sqrt(finalConditionedEnergy / frame.length) / Short.MAX_VALUE);
        return new Result(
                conditionedFrame,
                rawRms,
                conditionedRms,
                spectralFlux,
                zcr,
                lowBandRatio,
                highBandRatio,
                voicingScore,
                rubbingScore,
                speechScore
        );
    }

    void reset() {
        lowPassState = 0.0f;
        smoothedGain = 1.0f;
    }

    private float estimateVoicing(short[] frame) {
        if (frame.length <= MIN_PITCH_LAG) {
            return 0.0f;
        }

        double mean = 0.0d;
        for (short sample : frame) {
            mean += sample;
        }
        mean /= frame.length;

        double totalEnergy = 0.0d;
        for (short sample : frame) {
            double centered = sample - mean;
            totalEnergy += centered * centered;
        }
        if (totalEnergy <= 0.0d) {
            return 0.0f;
        }

        double bestCorrelation = 0.0d;
        int upperLag = Math.min(MAX_PITCH_LAG, frame.length / 2);
        for (int lag = MIN_PITCH_LAG; lag <= upperLag; lag += LAG_STEP) {
            double correlation = 0.0d;
            for (int index = lag; index < frame.length; index++) {
                double current = frame[index] - mean;
                double previous = frame[index - lag] - mean;
                correlation += current * previous;
            }
            if (correlation > bestCorrelation) {
                bestCorrelation = correlation;
            }
        }

        return MathUtils.clamp((float) (bestCorrelation / totalEnergy), 0.0f, 1.0f);
    }

    private short saturate(int sample) {
        if (sample > Short.MAX_VALUE) {
            return Short.MAX_VALUE;
        }
        if (sample < Short.MIN_VALUE) {
            return Short.MIN_VALUE;
        }
        return (short) sample;
    }
}
