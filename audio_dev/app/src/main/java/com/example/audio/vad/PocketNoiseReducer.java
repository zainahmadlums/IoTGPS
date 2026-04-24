package com.example.audio.vad;

import com.example.audio.util.MathUtils;

final class PocketNoiseReducer {

    private static final class PitchEstimate {
        private final float voicingScore;
        private final int pitchLag;

        PitchEstimate(float voicingScore, int pitchLag) {
            this.voicingScore = voicingScore;
            this.pitchLag = pitchLag;
        }
    }

    static final class Result {
        private final short[] conditionedFrame;
        private final short[] playbackFrame;
        private final float rawRms;
        private final float conditionedRms;
        private final float spectralFlux;
        private final float zcr;
        private final float lowBandRatio;
        private final float highBandRatio;
        private final float voicingScore;
        private final float rubbingScore;
        private final float speechScore;
        private final float occlusionScore;
        private final float unreliableScore;
        private final boolean rejectForVad;

        Result(
                short[] conditionedFrame,
                short[] playbackFrame,
                float rawRms,
                float conditionedRms,
                float spectralFlux,
                float zcr,
                float lowBandRatio,
                float highBandRatio,
                float voicingScore,
                float rubbingScore,
                float speechScore,
                float occlusionScore,
                float unreliableScore,
                boolean rejectForVad
        ) {
            this.conditionedFrame = conditionedFrame;
            this.playbackFrame = playbackFrame;
            this.rawRms = rawRms;
            this.conditionedRms = conditionedRms;
            this.spectralFlux = spectralFlux;
            this.zcr = zcr;
            this.lowBandRatio = lowBandRatio;
            this.highBandRatio = highBandRatio;
            this.voicingScore = voicingScore;
            this.rubbingScore = rubbingScore;
            this.speechScore = speechScore;
            this.occlusionScore = occlusionScore;
            this.unreliableScore = unreliableScore;
            this.rejectForVad = rejectForVad;
        }

        short[] getConditionedFrame() {
            return conditionedFrame;
        }

        short[] getPlaybackFrame() {
            return playbackFrame;
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

        float getOcclusionScore() {
            return occlusionScore;
        }

        float getUnreliableScore() {
            return unreliableScore;
        }

        boolean shouldRejectForVad() {
            return rejectForVad;
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
    private static final float HARD_GATE_DOMINANCE_THRESHOLD = 0.46f;
    private static final float HARD_GATE_SPEECH_EVIDENCE_THRESHOLD = 0.42f;
    private static final int MIN_PITCH_LAG = 32;
    private static final int MAX_PITCH_LAG = 160;
    private static final int LAG_STEP = 4;

    private float lowPassState;
    private float smoothedGain = 1.0f;

    Result condition(short[] frame, float spectralFlux, float zcr) {
        if (frame == null) {
            return new Result(
                    new short[0],
                    new short[0],
                    0.0f,
                    0.0f,
                    spectralFlux,
                    zcr,
                    0.0f,
                    0.0f,
                    0.0f,
                    0.0f,
                    0.0f,
                    0.0f,
                    0.0f,
                    false
            );
        }

        short[] conditionedFrame = new short[frame.length];
        short[] playbackFrame = new short[frame.length];
        if (frame.length == 0) {
            return new Result(
                    conditionedFrame,
                    playbackFrame,
                    0.0f,
                    0.0f,
                    spectralFlux,
                    zcr,
                    0.0f,
                    0.0f,
                    0.0f,
                    0.0f,
                    0.0f,
                    0.0f,
                    0.0f,
                    false
            );
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

        PitchEstimate pitchEstimate = estimatePitch(frame);
        float rawRms = (float) (Math.sqrt(rawEnergy / frame.length) / Short.MAX_VALUE);
        float lowBandRatio = rawEnergy > 0.0d ? (float) (lowEnergy / rawEnergy) : 0.0f;
        float highBandRatio = rawEnergy > 0.0d ? (float) (highEnergy / rawEnergy) : 0.0f;
        float voicingScore = pitchEstimate.voicingScore;
        float fluxScore = MathUtils.clamp(spectralFlux / 0.08f, 0.0f, 1.0f);
        float zcrScore = MathUtils.clamp(zcr / 0.22f, 0.0f, 1.0f);

        float speechEvidence = MathUtils.clamp(
                (0.48f * voicingScore)
                        + (0.32f * highBandRatio)
                        + (0.12f * (1.0f - Math.min(1.0f, fluxScore * 0.6f)))
                        + (0.08f * (1.0f - Math.min(1.0f, zcrScore * 0.5f))),
                0.0f,
                1.0f
        );
        float rubbingSignature = MathUtils.clamp(
                (0.32f * lowBandRatio)
                        + (0.26f * fluxScore)
                        + (0.18f * zcrScore)
                        + (0.24f * (1.0f - voicingScore)),
                0.0f,
                1.0f
        );
        float occlusionSignature = MathUtils.clamp(
                (0.42f * lowBandRatio)
                        + (0.30f * (1.0f - highBandRatio))
                        + (0.16f * (1.0f - Math.min(1.0f, fluxScore * 1.1f)))
                        + (0.12f * (1.0f - Math.min(1.0f, zcrScore * 1.1f))),
                0.0f,
                1.0f
        );
        float rubbingDominance = MathUtils.clamp(
                (0.62f * rubbingSignature)
                        + (0.22f * lowBandRatio)
                        + (0.10f * (1.0f - highBandRatio))
                        - (0.52f * speechEvidence)
                        - (0.12f * voicingScore),
                0.0f,
                1.0f
        );
        float speechScore = MathUtils.clamp(
                speechEvidence - (0.28f * rubbingDominance),
                0.0f,
                1.0f
        );
        float rubbingScore = MathUtils.clamp(
                rubbingSignature + (0.35f * rubbingDominance),
                0.0f,
                1.0f
        );
        float occlusionScore = MathUtils.clamp(
                (0.64f * occlusionSignature)
                        + (0.14f * (1.0f - Math.min(1.0f, rawRms / 0.045f)))
                        + (0.10f * (1.0f - speechEvidence))
                        - (0.22f * voicingScore)
                        - (0.10f * highBandRatio),
                0.0f,
                1.0f
        );
        float unreliableScore = MathUtils.clamp(
                (0.58f * rubbingDominance)
                        + (0.42f * occlusionScore)
                        - (0.44f * speechEvidence)
                        - (0.16f * voicingScore),
                0.0f,
                1.0f
        );
        float rubbingGate = MathUtils.clamp(
                (rubbingDominance - 0.26f) / 0.54f,
                0.0f,
                1.0f
        );
        float unreliableGate = MathUtils.clamp(
                (unreliableScore - 0.24f) / 0.56f,
                0.0f,
                1.0f
        );
        boolean rejectForVad = unreliableScore >= 0.50f
                && speechEvidence <= 0.52f
                && !(voicingScore >= 0.56f && highBandRatio >= 0.22f);
        boolean hardRubbingGate = rubbingDominance >= HARD_GATE_DOMINANCE_THRESHOLD
                && speechEvidence <= HARD_GATE_SPEECH_EVIDENCE_THRESHOLD;
        boolean hardSuppressionGate = hardRubbingGate || (rejectForVad && occlusionScore >= 0.56f);
        float suppressionGate = MathUtils.clamp(
                Math.max(rubbingGate, 0.82f * unreliableGate),
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
                1.0f - (0.97f * suppressionGate * (1.0f - (0.88f * speechProtection))),
                hardSuppressionGate ? 0.02f : MIN_LOW_BAND_ATTENUATION,
                1.0f
        );
        float highBandAttenuation = MathUtils.clamp(
                1.0f - (0.52f * suppressionGate * (1.0f - (0.82f * speechProtection))),
                hardSuppressionGate ? 0.24f : MIN_HIGH_BAND_ATTENUATION,
                1.0f
        );
        float ambientFloorMix = MathUtils.clamp(
                BASE_AMBIENT_FLOOR_MIX
                        + (0.10f * speechProtection)
                        - (0.24f * suppressionGate),
                hardSuppressionGate ? 0.0f : MIN_AMBIENT_FLOOR_MIX,
                MAX_AMBIENT_FLOOR_MIX
        );
        float playbackLowBandAttenuation = MathUtils.clamp(
                1.0f - (0.84f * suppressionGate * (1.0f - (0.60f * speechProtection))),
                hardSuppressionGate ? 0.08f : 0.32f,
                1.0f
        );
        float playbackHighBandAttenuation = MathUtils.clamp(
                1.0f - (0.30f * suppressionGate * (1.0f - (0.74f * speechProtection))),
                hardSuppressionGate ? 0.48f : 0.82f,
                1.0f
        );
        float playbackAmbientMix = MathUtils.clamp(
                0.46f - (0.42f * suppressionGate) + (0.18f * speechProtection),
                hardSuppressionGate ? 0.02f : 0.10f,
                0.74f
        );
        float conditionedFrameGain = MathUtils.clamp(
                1.0f - (0.82f * suppressionGate * (1.0f - (0.70f * speechProtection))),
                hardSuppressionGate ? 0.06f : 0.32f,
                1.0f
        );
        float playbackFrameGain = MathUtils.clamp(
                1.0f - (0.72f * suppressionGate * (1.0f - (0.78f * speechProtection))),
                hardSuppressionGate ? 0.12f : 0.46f,
                1.0f
        );
        if (hardSuppressionGate && voicingScore < 0.18f) {
            conditionedFrameGain = Math.min(conditionedFrameGain, 0.04f);
            playbackFrameGain = Math.min(playbackFrameGain, 0.10f);
        }
        float[] speechEstimate = buildSpeechEstimate(
                frame,
                highBand,
                pitchEstimate.pitchLag,
                speechProtection,
                voicingScore
        );
        float speechIsolationMix = MathUtils.clamp(
                (0.78f * suppressionGate) + (0.12f * (1.0f - speechProtection)),
                0.0f,
                0.96f
        );
        float playbackSpeechIsolationMix = MathUtils.clamp(
                (0.92f * suppressionGate) + (0.08f * (1.0f - speechProtection)),
                0.0f,
                0.98f
        );

        double conditionedEnergy = 0.0d;
        float[] blendedFrame = new float[frame.length];
        for (int index = 0; index < frame.length; index++) {
            float suppressed = (lowBand[index] * lowBandAttenuation)
                    + (highBand[index] * highBandAttenuation);
            float blended = (ambientFloorMix * frame[index])
                    + ((1.0f - ambientFloorMix) * suppressed);
            float speechFocused = speechEstimate[index]
                    * MathUtils.clamp(0.52f + (0.58f * speechProtection), 0.32f, 1.0f);
            blended = ((1.0f - speechIsolationMix) * blended)
                    + (speechIsolationMix * speechFocused);
            blended *= conditionedFrameGain;
            blendedFrame[index] = blended;
            conditionedEnergy += blended * blended;

            float playbackSuppressed = (lowBand[index] * playbackLowBandAttenuation)
                    + (highBand[index] * playbackHighBandAttenuation);
            float playbackBlended = (playbackAmbientMix * frame[index])
                    + ((1.0f - playbackAmbientMix) * playbackSuppressed);
            playbackBlended = ((1.0f - playbackSpeechIsolationMix) * playbackBlended)
                    + (playbackSpeechIsolationMix * speechEstimate[index]);
            playbackBlended *= playbackFrameGain;
            playbackFrame[index] = saturate(Math.round(playbackBlended));
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
                playbackFrame,
                rawRms,
                conditionedRms,
                spectralFlux,
                zcr,
                lowBandRatio,
                highBandRatio,
                voicingScore,
                rubbingScore,
                speechScore,
                occlusionScore,
                unreliableScore,
                rejectForVad
        );
    }

    void reset() {
        lowPassState = 0.0f;
        smoothedGain = 1.0f;
    }

    private PitchEstimate estimatePitch(short[] frame) {
        if (frame.length <= MIN_PITCH_LAG) {
            return new PitchEstimate(0.0f, -1);
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
            return new PitchEstimate(0.0f, -1);
        }

        double bestCorrelation = 0.0d;
        int bestLag = -1;
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
                bestLag = lag;
            }
        }

        return new PitchEstimate(
                MathUtils.clamp((float) (bestCorrelation / totalEnergy), 0.0f, 1.0f),
                bestLag
        );
    }

    private float[] buildSpeechEstimate(
            short[] frame,
            float[] highBand,
            int pitchLag,
            float speechProtection,
            float voicingScore
    ) {
        float[] speechEstimate = new float[frame.length];
        for (int index = 0; index < frame.length; index++) {
            float periodic = frame[index];
            int sampleCount = 1;
            if (pitchLag > 0 && index >= pitchLag) {
                periodic += frame[index - pitchLag];
                sampleCount++;
            }
            if (pitchLag > 0 && index + pitchLag < frame.length) {
                periodic += frame[index + pitchLag];
                sampleCount++;
            }
            periodic /= sampleCount;

            float harmonicComponent = (pitchLag > 0 ? periodic : frame[index]);
            float unvoicedComponent = highBand[index];
            float harmonicMix = MathUtils.clamp(
                    (0.25f + (0.70f * voicingScore) + (0.18f * speechProtection)),
                    0.18f,
                    0.96f
            );
            speechEstimate[index] = (harmonicMix * harmonicComponent)
                    + ((1.0f - harmonicMix) * unvoicedComponent);
        }
        return speechEstimate;
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
