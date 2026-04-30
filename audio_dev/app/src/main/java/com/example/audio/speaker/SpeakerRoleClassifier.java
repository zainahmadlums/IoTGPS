package com.example.audio.speaker;

import com.example.audio.data.InstructorVoiceProfile;
import com.example.audio.data.SpeakerRole;
import com.example.audio.util.Logger;
import com.example.audio.vad.VadResult;

public final class SpeakerRoleClassifier {

    private static final String TAG = "SpeakerRoleClassifier";
    private static final int ROLLING_WINDOW_FRAMES = 10;
    private static final int MIN_EMBEDDING_FRAMES = 3;
    private static final float INSTRUCTOR_THRESHOLD = 0.86f;
    private static final float BOTH_MIN_THRESHOLD = 0.55f;
    private static final float BOTH_MAX_THRESHOLD = 0.86f;
    private static final float MIN_ROLE_RMS = 0.012f;
    private static final float MIN_ROLE_CONFIDENCE = 0.42f;
    private static final float OVERLAP_RMS_JUMP = 1.65f;
    private static final float OVERLAP_BAND_SHIFT = 0.22f;
    private static final long LOG_INTERVAL_MILLIS = 1000L;

    private final SpeakerEmbeddingExtractor embeddingExtractor = new SpeakerEmbeddingExtractor();
    private final SpeakerEmbeddingExtractor.RollingWindow rollingWindow =
            embeddingExtractor.new RollingWindow(ROLLING_WINDOW_FRAMES);
    private final InstructorVoiceProfile instructorVoiceProfile;
    private final float[] instructorEmbedding;
    private SpeakerRole lastStableSpeechRole = SpeakerRole.INSTRUCTOR;
    private float previousInstructorSimilarity = -1.0f;
    private long lastLogTimestampMillis;

    public SpeakerRoleClassifier(InstructorVoiceProfile instructorVoiceProfile) {
        this.instructorVoiceProfile = instructorVoiceProfile;
        this.instructorEmbedding = instructorVoiceProfile == null
                ? new float[0]
                : instructorVoiceProfile.getSpeakerEmbedding();
    }

    public SpeakerRole classify(short[] frame, VadResult vadResult) {
        if (vadResult == null || !vadResult.isSpeech()) {
            rollingWindow.clear();
            previousInstructorSimilarity = -1.0f;
            return SpeakerRole.SILENCE;
        }
        if (instructorVoiceProfile == null
                || instructorEmbedding.length == 0
                || frame == null
                || frame.length == 0) {
            return SpeakerRole.STUDENT;
        }

        FrameVoiceFeatures features = extractFeatures(frame);
        float vadConfidence = vadResult.getConfidence() == null ? 0.0f : vadResult.getConfidence();
        if (features.rms < MIN_ROLE_RMS || vadConfidence < MIN_ROLE_CONFIDENCE) {
            rollingWindow.clear();
            previousInstructorSimilarity = -1.0f;
            logDecision(vadResult, SpeakerRole.SILENCE, 0.0f, features, "weak-speech");
            return SpeakerRole.SILENCE;
        }

        rollingWindow.add(frame);
        if (rollingWindow.size() < MIN_EMBEDDING_FRAMES) {
            logDecision(vadResult, lastStableSpeechRole, 0.0f, features, "warmup");
            return lastStableSpeechRole;
        }

        float[] currentEmbedding = rollingWindow.buildEmbedding();
        float instructorSimilarity = embeddingExtractor.cosineSimilarity(currentEmbedding, instructorEmbedding);
        boolean mixedEnergy = Math.abs(features.rms - instructorVoiceProfile.getAverageRms()) > 0.085f;
        boolean strongSpeech = vadResult.getConfidence() != null && vadResult.getConfidence() >= 0.72f;
        boolean overlapEnergyJump = instructorVoiceProfile.getAverageRms() > 0.0f
                && features.rms >= instructorVoiceProfile.getAverageRms() * OVERLAP_RMS_JUMP;
        boolean overlapBandShift =
                Math.abs(features.lowBandRatio - instructorVoiceProfile.getAverageLowBandRatio()) >= OVERLAP_BAND_SHIFT
                        || Math.abs(features.highBandRatio - instructorVoiceProfile.getAverageHighBandRatio()) >= OVERLAP_BAND_SHIFT;
        boolean similarityUnstable = previousInstructorSimilarity >= 0.0f
                && Math.abs(instructorSimilarity - previousInstructorSimilarity) >= 0.16f;
        previousInstructorSimilarity = instructorSimilarity;

        if (strongSpeech
                && instructorSimilarity >= BOTH_MIN_THRESHOLD
                && instructorSimilarity < BOTH_MAX_THRESHOLD
                && (mixedEnergy || overlapEnergyJump || overlapBandShift || similarityUnstable)) {
            lastStableSpeechRole = SpeakerRole.BOTH;
            logDecision(vadResult, SpeakerRole.BOTH, instructorSimilarity, features, "mixed");
            return SpeakerRole.BOTH;
        }
        if (instructorSimilarity >= INSTRUCTOR_THRESHOLD) {
            lastStableSpeechRole = SpeakerRole.INSTRUCTOR;
            logDecision(vadResult, SpeakerRole.INSTRUCTOR, instructorSimilarity, features, "match");
            return SpeakerRole.INSTRUCTOR;
        }
        lastStableSpeechRole = SpeakerRole.STUDENT;
        logDecision(vadResult, SpeakerRole.STUDENT, instructorSimilarity, features, "mismatch");
        return SpeakerRole.STUDENT;
    }

    private void logDecision(
            VadResult vadResult,
            SpeakerRole speakerRole,
            float instructorSimilarity,
            FrameVoiceFeatures features,
            String reason
    ) {
        long timestampMillis = vadResult.getTimestampMillis();
        if (timestampMillis - lastLogTimestampMillis < LOG_INTERVAL_MILLIS) {
            return;
        }
        lastLogTimestampMillis = timestampMillis;
        Logger.d(
                TAG,
                "role="
                        + speakerRole
                        + ", reason="
                        + reason
                        + ", instructorSimilarity="
                        + instructorSimilarity
                        + ", vadConfidence="
                        + vadResult.getConfidence()
                        + ", rms="
                        + features.rms
                        + ", zcr="
                        + features.zcr
                        + ", lowBandRatio="
                        + features.lowBandRatio
                        + ", highBandRatio="
                        + features.highBandRatio
        );
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
