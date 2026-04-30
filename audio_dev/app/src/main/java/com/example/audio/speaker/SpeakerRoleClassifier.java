package com.example.audio.speaker;

import com.example.audio.data.InstructorVoiceProfile;
import com.example.audio.data.SpeakerRole;
import com.example.audio.util.Logger;
import com.example.audio.vad.VadResult;

import java.util.ArrayList;
import java.util.List;

public final class SpeakerRoleClassifier {

    private static final String TAG = "SpeakerRoleClassifier";
    private static final int SHORT_WINDOW_FRAMES = 8;
    private static final int IDENTITY_WINDOW_FRAMES = 32;
    private static final int MIN_EMBEDDING_FRAMES = 8;
    private static final int MAX_STUDENT_CLUSTERS = 6;
    private static final int MIN_STUDENT_CLUSTER_FRAMES = 2;
    private static final float INSTRUCTOR_ENTER_THRESHOLD = 0.93f;
    private static final float INSTRUCTOR_STAY_THRESHOLD = 0.90f;
    private static final float INSTRUCTOR_STRICT_THRESHOLD = 0.96f;
    private static final float STUDENT_CLUSTER_THRESHOLD = 0.88f;
    private static final float STUDENT_CLUSTER_UPDATE_THRESHOLD = 0.82f;
    private static final float SPEAKER_MARGIN = 0.035f;
    private static final float BOTH_MIN_INSTRUCTOR_SIMILARITY = 0.78f;
    private static final float BOTH_MIN_STUDENT_SIMILARITY = 0.78f;
    private static final float MIN_ROLE_RMS = 0.012f;
    private static final float MIN_ROLE_CONFIDENCE = 0.42f;
    private static final float OVERLAP_RMS_JUMP = 1.45f;
    private static final float OVERLAP_BAND_SHIFT = 0.22f;
    private static final int ROLE_SMOOTHING_FRAMES = 3;
    private static final long LOG_INTERVAL_MILLIS = 1000L;

    private final SpeakerEmbeddingExtractor embeddingExtractor = new SpeakerEmbeddingExtractor();
    private final SpeakerEmbeddingExtractor.RollingWindow shortWindow =
            embeddingExtractor.new RollingWindow(SHORT_WINDOW_FRAMES);
    private final SpeakerEmbeddingExtractor.RollingWindow identityWindow =
            embeddingExtractor.new RollingWindow(IDENTITY_WINDOW_FRAMES);
    private final List<SpeakerPrototype> studentPrototypes = new ArrayList<>();
    private final InstructorVoiceProfile instructorVoiceProfile;
    private final float[] instructorEmbedding;
    private final SpeakerRoleModel speakerRoleModel;
    private SpeakerRole lastStableSpeechRole = SpeakerRole.STUDENT;
    private SpeakerRole pendingRole = SpeakerRole.SILENCE;
    private int pendingRoleFrames;
    private float previousInstructorSimilarity = -1.0f;
    private long lastLogTimestampMillis;

    public SpeakerRoleClassifier(InstructorVoiceProfile instructorVoiceProfile) {
        this(instructorVoiceProfile, null);
    }

    public SpeakerRoleClassifier(InstructorVoiceProfile instructorVoiceProfile, SpeakerRoleModel speakerRoleModel) {
        this.instructorVoiceProfile = instructorVoiceProfile;
        this.instructorEmbedding = instructorVoiceProfile == null
                ? new float[0]
                : instructorVoiceProfile.getSpeakerEmbedding();
        this.speakerRoleModel = speakerRoleModel;
    }

    public SpeakerRole classify(short[] frame, VadResult vadResult) {
        if (vadResult == null || !vadResult.isSpeech()) {
            shortWindow.clear();
            identityWindow.clear();
            previousInstructorSimilarity = -1.0f;
            pendingRole = SpeakerRole.SILENCE;
            pendingRoleFrames = 0;
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
            shortWindow.clear();
            identityWindow.clear();
            previousInstructorSimilarity = -1.0f;
            logDecision(vadResult, SpeakerRole.SILENCE, 0.0f, 0.0f, features, "weak-speech");
            return SpeakerRole.SILENCE;
        }

        shortWindow.add(frame);
        identityWindow.add(frame);
        float[] currentEmbedding = identityWindow.size() >= MIN_EMBEDDING_FRAMES
                ? identityWindow.buildEmbedding()
                : shortWindow.buildEmbedding();
        float instructorSimilarity = embeddingExtractor.cosineSimilarity(currentEmbedding, instructorEmbedding);
        StudentMatch studentMatch = findBestStudentMatch(currentEmbedding);
        boolean strongSpeech = vadResult.getConfidence() != null && vadResult.getConfidence() >= 0.72f;
        boolean overlapEnergyJump = instructorVoiceProfile.getAverageRms() > 0.0f
                && features.rms >= instructorVoiceProfile.getAverageRms() * OVERLAP_RMS_JUMP;
        boolean overlapBandShift =
                Math.abs(features.lowBandRatio - instructorVoiceProfile.getAverageLowBandRatio()) >= OVERLAP_BAND_SHIFT
                        || Math.abs(features.highBandRatio - instructorVoiceProfile.getAverageHighBandRatio()) >= OVERLAP_BAND_SHIFT;
        boolean similarityUnstable = previousInstructorSimilarity >= 0.0f
                && Math.abs(instructorSimilarity - previousInstructorSimilarity) >= 0.16f;
        previousInstructorSimilarity = instructorSimilarity;

        SpeakerRole rawRole = classifyEmbedding(
                instructorSimilarity,
                studentMatch.similarity,
                buildModelFeatures(
                        instructorSimilarity,
                        studentMatch.similarity,
                        features,
                        vadConfidence,
                        overlapEnergyJump,
                        overlapBandShift,
                        similarityUnstable
                ),
                strongSpeech,
                overlapEnergyJump,
                overlapBandShift,
                similarityUnstable
        );
        if (rawRole == SpeakerRole.STUDENT) {
            updateStudentPrototypes(currentEmbedding, studentMatch);
        }

        SpeakerRole smoothedRole = smoothRole(rawRole);
        if (smoothedRole != SpeakerRole.SILENCE) {
            lastStableSpeechRole = smoothedRole;
        }
        logDecision(
                vadResult,
                smoothedRole,
                instructorSimilarity,
                studentMatch.similarity,
                features,
                rawRole == smoothedRole ? "diarized" : "smoothing"
        );
        return smoothedRole;
    }

    private SpeakerRole classifyEmbedding(
            float instructorSimilarity,
            float studentSimilarity,
            float[] modelFeatures,
            boolean strongSpeech,
            boolean overlapEnergyJump,
            boolean overlapBandShift,
            boolean similarityUnstable
    ) {
        if (speakerRoleModel != null) {
            return speakerRoleModel.predict(modelFeatures);
        }

        boolean knownStudent = studentSimilarity >= STUDENT_CLUSTER_THRESHOLD;
        boolean instructorDominant = instructorSimilarity >= INSTRUCTOR_ENTER_THRESHOLD
                && instructorSimilarity >= studentSimilarity + SPEAKER_MARGIN;
        boolean instructorSticky = lastStableSpeechRole == SpeakerRole.INSTRUCTOR
                && instructorSimilarity >= INSTRUCTOR_STAY_THRESHOLD
                && instructorSimilarity >= studentSimilarity;
        boolean overlapCandidate = strongSpeech
                && instructorSimilarity >= BOTH_MIN_INSTRUCTOR_SIMILARITY
                && studentSimilarity >= BOTH_MIN_STUDENT_SIMILARITY
                && Math.abs(instructorSimilarity - studentSimilarity) <= 0.10f
                && (overlapEnergyJump || overlapBandShift || similarityUnstable);

        if (overlapCandidate) {
            return SpeakerRole.BOTH;
        }
        if (knownStudent && studentSimilarity >= instructorSimilarity - SPEAKER_MARGIN) {
            return SpeakerRole.STUDENT;
        }
        if (instructorDominant || instructorSticky || instructorSimilarity >= INSTRUCTOR_STRICT_THRESHOLD) {
            return SpeakerRole.INSTRUCTOR;
        }
        return SpeakerRole.STUDENT;
    }

    private float[] buildModelFeatures(
            float instructorSimilarity,
            float studentSimilarity,
            FrameVoiceFeatures features,
            float vadConfidence,
            boolean overlapEnergyJump,
            boolean overlapBandShift,
            boolean similarityUnstable
    ) {
        float instructorRms = instructorVoiceProfile == null ? 0.0f : instructorVoiceProfile.getAverageRms();
        float lowBandDelta = instructorVoiceProfile == null
                ? 0.0f
                : Math.abs(features.lowBandRatio - instructorVoiceProfile.getAverageLowBandRatio());
        float highBandDelta = instructorVoiceProfile == null
                ? 0.0f
                : Math.abs(features.highBandRatio - instructorVoiceProfile.getAverageHighBandRatio());
        return new float[]{
                instructorSimilarity,
                studentSimilarity,
                instructorSimilarity - studentSimilarity,
                features.rms,
                features.zcr,
                features.lowBandRatio,
                features.highBandRatio,
                vadConfidence,
                instructorRms > 0.0f ? features.rms / instructorRms : 0.0f,
                lowBandDelta,
                highBandDelta,
                overlapEnergyJump ? 1.0f : 0.0f,
                overlapBandShift ? 1.0f : 0.0f,
                similarityUnstable ? 1.0f : 0.0f,
                studentPrototypes.size()
        };
    }

    private SpeakerRole smoothRole(SpeakerRole rawRole) {
        if (rawRole == pendingRole) {
            pendingRoleFrames++;
        } else {
            pendingRole = rawRole;
            pendingRoleFrames = 1;
        }

        if (rawRole == SpeakerRole.BOTH || pendingRoleFrames >= ROLE_SMOOTHING_FRAMES) {
            return rawRole;
        }
        if (lastStableSpeechRole == SpeakerRole.SILENCE) {
            return rawRole;
        }
        return lastStableSpeechRole;
    }

    private StudentMatch findBestStudentMatch(float[] embedding) {
        int bestIndex = -1;
        float bestSimilarity = 0.0f;
        for (int index = 0; index < studentPrototypes.size(); index++) {
            float similarity = embeddingExtractor.cosineSimilarity(embedding, studentPrototypes.get(index).centroid);
            if (similarity > bestSimilarity) {
                bestSimilarity = similarity;
                bestIndex = index;
            }
        }
        return new StudentMatch(bestIndex, bestSimilarity);
    }

    private void updateStudentPrototypes(float[] embedding, StudentMatch studentMatch) {
        if (embedding == null || embedding.length == 0) {
            return;
        }
        if (studentMatch.index >= 0 && studentMatch.similarity >= STUDENT_CLUSTER_UPDATE_THRESHOLD) {
            studentPrototypes.get(studentMatch.index).update(embedding, embeddingExtractor);
            return;
        }
        if (studentPrototypes.size() < MAX_STUDENT_CLUSTERS) {
            studentPrototypes.add(new SpeakerPrototype(embedding));
        }
    }

    private void logDecision(
            VadResult vadResult,
            SpeakerRole speakerRole,
            float instructorSimilarity,
            float studentSimilarity,
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
                        + ", studentSimilarity="
                        + studentSimilarity
                        + ", studentClusters="
                        + studentPrototypes.size()
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

    private static final class StudentMatch {
        private final int index;
        private final float similarity;

        StudentMatch(int index, float similarity) {
            this.index = index;
            this.similarity = similarity;
        }
    }

    private static final class SpeakerPrototype {
        private float[] centroid;
        private int frameCount;

        SpeakerPrototype(float[] embedding) {
            this.centroid = embedding.clone();
            this.frameCount = 1;
        }

        void update(float[] embedding, SpeakerEmbeddingExtractor embeddingExtractor) {
            int updateWeight = Math.min(frameCount, MIN_STUDENT_CLUSTER_FRAMES);
            for (int index = 0; index < centroid.length; index++) {
                centroid[index] = ((centroid[index] * updateWeight) + embedding[index]) / (updateWeight + 1);
            }
            centroid = embeddingExtractor.l2Normalize(centroid);
            frameCount++;
        }
    }
}
