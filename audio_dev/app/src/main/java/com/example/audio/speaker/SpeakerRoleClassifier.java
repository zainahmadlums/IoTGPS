package com.example.audio.speaker;

import com.example.audio.data.InstructorVoiceProfile;
import com.example.audio.data.SpeakerRole;
import com.example.audio.vad.VadResult;

public final class SpeakerRoleClassifier {

    private final InstructorVoiceProfile instructorVoiceProfile;

    public SpeakerRoleClassifier(InstructorVoiceProfile instructorVoiceProfile) {
        this.instructorVoiceProfile = instructorVoiceProfile;
    }

    public SpeakerRole classify(short[] frame, VadResult vadResult) {
        if (vadResult == null || !vadResult.isSpeech()) {
            return SpeakerRole.SILENCE;
        }
        if (instructorVoiceProfile == null || frame == null || frame.length == 0) {
            return SpeakerRole.STUDENT;
        }

        FrameVoiceFeatures features = extractFeatures(frame);
        float instructorSimilarity = similarity(features);
        boolean mixedEnergy = Math.abs(features.rms - instructorVoiceProfile.getAverageRms()) > 0.075f;
        boolean mixedBands = Math.abs(features.lowBandRatio - instructorVoiceProfile.getAverageLowBandRatio()) > 0.28f
                && Math.abs(features.highBandRatio - instructorVoiceProfile.getAverageHighBandRatio()) > 0.28f;
        boolean strongSpeech = vadResult.getConfidence() != null && vadResult.getConfidence() >= 0.72f;

        if (strongSpeech && instructorSimilarity >= 0.54f && (mixedEnergy || mixedBands)) {
            return SpeakerRole.BOTH;
        }
        if (instructorSimilarity >= 0.58f) {
            return SpeakerRole.INSTRUCTOR;
        }
        return SpeakerRole.STUDENT;
    }

    private float similarity(FrameVoiceFeatures features) {
        float rmsDistance = normalizedDistance(features.rms, instructorVoiceProfile.getAverageRms(), 0.12f);
        float zcrDistance = normalizedDistance(features.zcr, instructorVoiceProfile.getAverageZcr(), 0.18f);
        float lowDistance = normalizedDistance(
                features.lowBandRatio,
                instructorVoiceProfile.getAverageLowBandRatio(),
                0.45f
        );
        float highDistance = normalizedDistance(
                features.highBandRatio,
                instructorVoiceProfile.getAverageHighBandRatio(),
                0.45f
        );
        float distance = (0.24f * rmsDistance)
                + (0.20f * zcrDistance)
                + (0.28f * lowDistance)
                + (0.28f * highDistance);
        return Math.max(0.0f, Math.min(1.0f, 1.0f - distance));
    }

    private float normalizedDistance(float value, float reference, float scale) {
        if (scale <= 0.0f) {
            return 1.0f;
        }
        return Math.max(0.0f, Math.min(1.0f, Math.abs(value - reference) / scale));
    }

    private FrameVoiceFeatures extractFeatures(short[] frame) {
        double energy = 0.0d;
        double lowEnergy = 0.0d;
        double highEnergy = 0.0d;
        int crossings = 0;
        float lowPassState = 0.0f;
        short previous = frame[0];

        for (short sample : frame) {
            energy += (double) sample * sample;
            lowPassState += 0.12f * (sample - lowPassState);
            float lowComponent = lowPassState;
            float highComponent = sample - lowComponent;
            lowEnergy += lowComponent * lowComponent;
            highEnergy += highComponent * highComponent;
            if ((previous >= 0 && sample < 0) || (previous < 0 && sample >= 0)) {
                crossings++;
            }
            previous = sample;
        }

        float rms = (float) (Math.sqrt(energy / frame.length) / Short.MAX_VALUE);
        float zcr = (float) crossings / frame.length;
        float lowBandRatio = energy > 0.0d ? (float) (lowEnergy / energy) : 0.0f;
        float highBandRatio = energy > 0.0d ? (float) (highEnergy / energy) : 0.0f;
        return new FrameVoiceFeatures(rms, zcr, lowBandRatio, highBandRatio);
    }

    private static final class FrameVoiceFeatures {
        private final float rms;
        private final float zcr;
        private final float lowBandRatio;
        private final float highBandRatio;

        FrameVoiceFeatures(float rms, float zcr, float lowBandRatio, float highBandRatio) {
            this.rms = rms;
            this.zcr = zcr;
            this.lowBandRatio = lowBandRatio;
            this.highBandRatio = highBandRatio;
        }
    }
}
