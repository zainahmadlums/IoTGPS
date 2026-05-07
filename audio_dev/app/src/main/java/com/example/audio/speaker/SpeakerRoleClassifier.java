package com.example.audio.speaker;

import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor;
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig;
import com.k2fsa.sherpa.onnx.OnlineStream;

import com.example.audio.data.InstructorVoiceProfile;
import com.example.audio.data.SessionAudioFileManager;
import com.example.audio.data.SpeakerRole;
import com.example.audio.util.Logger;
import com.example.audio.vad.VadResult;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

public final class SpeakerRoleClassifier implements SpeakerDiarizer {

    private static final String TAG = "SpeakerRoleClassifier";
    private static final int SPEAKER_WINDOW_FRAMES = 64;
    private static final int SPEAKER_HOP_FRAMES = 32;
    private static final int EMBEDDING_SAMPLE_FRAMES = 16;
    private static final int MAX_SPEECH_GAP_FRAMES = 10;
    private static final int MAX_STUDENT_CLUSTERS = 6;
    private static final int MIN_STUDENT_CLUSTER_FRAMES = 2;
    private static final float INSTRUCTOR_ENTER_THRESHOLD = 0.97f;
    private static final float INSTRUCTOR_STAY_THRESHOLD = 0.96f;
    private static final float INSTRUCTOR_STRICT_THRESHOLD = 0.975f;
    private static final float STUDENT_CLUSTER_THRESHOLD = 0.75f;
    private static final float STUDENT_CLUSTER_UPDATE_THRESHOLD = 0.70f;
    private static final float SPEAKER_MARGIN = 0.035f;
    private static final float BOTH_MIN_INSTRUCTOR_SIMILARITY = 0.78f;
    private static final float BOTH_MIN_STUDENT_SIMILARITY = 0.78f;
    private static final float SHERPA_INSTRUCTOR_ENTER_THRESHOLD = 0.42f;
    private static final float SHERPA_INSTRUCTOR_STAY_THRESHOLD = 0.38f;
    private static final float SHERPA_STUDENT_CLUSTER_THRESHOLD = 0.36f;
    private static final float SHERPA_STUDENT_CLUSTER_UPDATE_THRESHOLD = 0.32f;
    private static final float SHERPA_BOTH_MIN_INSTRUCTOR_SIMILARITY = 0.36f;
    private static final float SHERPA_BOTH_MIN_STUDENT_SIMILARITY = 0.32f;
    private static final float MIN_ROLE_RMS = 0.012f;
    private static final float MIN_ROLE_CONFIDENCE = 0.42f;
    private static final float OVERLAP_RMS_JUMP = 1.30f;
    private static final float OVERLAP_BAND_SHIFT = 0.18f;
    private static final int ROLE_SMOOTHING_WINDOWS = 1;
    private static final long LOG_INTERVAL_MILLIS = 1000L;

    private final LegacySpeakerEmbeddingExtractor legacyExtractor = new LegacySpeakerEmbeddingExtractor();
    private SpeakerEmbeddingExtractor sherpaExtractor;
    private float[] sherpaInstructorEmbedding = new float[0];
    private final List<short[]> speechWindowFrames = new ArrayList<>();
    private final List<SpeakerPrototype> studentPrototypes = new ArrayList<>();
    private final InstructorVoiceProfile instructorVoiceProfile;
    private final float[] instructorEmbedding;
    private final SpeakerRoleModel speakerRoleModel;
    private SpeakerRole lastStableSpeechRole = SpeakerRole.STUDENT;
    private SpeakerRole pendingRole = SpeakerRole.SILENCE;
    private int pendingRoleFrames;
    private boolean activeSegmentHasDecision;
    private int speechGapFrames;
    private float previousInstructorSimilarity = -1.0f;
    private long lastLogTimestampMillis;

    public SpeakerRoleClassifier(android.content.Context context, InstructorVoiceProfile instructorVoiceProfile) {
        this(context, instructorVoiceProfile, null);
    }

    public SpeakerRoleClassifier(android.content.Context context, InstructorVoiceProfile instructorVoiceProfile, SpeakerRoleModel speakerRoleModel) {
        this.instructorVoiceProfile = instructorVoiceProfile;
        this.instructorEmbedding = instructorVoiceProfile == null
                ? new float[0]
                : instructorVoiceProfile.getSpeakerEmbedding();
        initSherpa(context);

        this.speakerRoleModel = speakerRoleModel;
    }

    public SpeakerRole classify(short[] frame, VadResult vadResult) {
        if (vadResult == null || !vadResult.isSpeech()) {
            return handleNonSpeechFrame();
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
            logDecision(vadResult, SpeakerRole.SILENCE, 0.0f, 0.0f, features, "weak-speech");
            return handleNonSpeechFrame();
        }

        speechGapFrames = 0;
        speechWindowFrames.add(frame.clone());
        if (speechWindowFrames.size() < SPEAKER_WINDOW_FRAMES) {
            return activeSegmentHasDecision ? lastStableSpeechRole : SpeakerRole.SILENCE;
        }

        short[] speakerWindow = buildSpeakerWindow();
        float[] currentEmbedding = buildWindowEmbedding();
        trimConsumedSpeechFrames();
        features = extractFeatures(speakerWindow);
        float instructorSimilarity = legacyExtractor.cosineSimilarity(currentEmbedding, instructorEmbedding);
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
                similarityUnstable,
                isSherpaEmbedding(currentEmbedding)
        );
        if (rawRole == SpeakerRole.STUDENT) {
            updateStudentPrototypes(currentEmbedding, studentMatch);
        }

        SpeakerRole smoothedRole = smoothRole(rawRole);
        if (smoothedRole != SpeakerRole.SILENCE) {
            lastStableSpeechRole = smoothedRole;
            activeSegmentHasDecision = true;
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

    private SpeakerRole handleNonSpeechFrame() {
        if (!speechWindowFrames.isEmpty() && speechGapFrames < MAX_SPEECH_GAP_FRAMES) {
            speechGapFrames++;
            return activeSegmentHasDecision ? lastStableSpeechRole : SpeakerRole.SILENCE;
        }
        speechWindowFrames.clear();
        previousInstructorSimilarity = -1.0f;
        pendingRole = SpeakerRole.SILENCE;
        pendingRoleFrames = 0;
        activeSegmentHasDecision = false;
        speechGapFrames = 0;
        return SpeakerRole.SILENCE;
    }

    private short[] buildSpeakerWindow() {
        int sampleCount = 0;
        for (short[] frame : speechWindowFrames) {
            sampleCount += frame.length;
        }
        short[] window = new short[sampleCount];
        int offset = 0;
        for (short[] frame : speechWindowFrames) {
            System.arraycopy(frame, 0, window, offset, frame.length);
            offset += frame.length;
        }
        return window;
    }

    private void trimConsumedSpeechFrames() {
        int removeCount = Math.min(SPEAKER_HOP_FRAMES, speechWindowFrames.size());
        for (int index = 0; index < removeCount; index++) {
            speechWindowFrames.remove(0);
        }
    }

    private float[] buildWindowEmbedding() {
        float[] sherpaEmbedding = buildSherpaEmbedding(buildSpeakerWindow());
        if (sherpaEmbedding.length > 0 && sherpaInstructorEmbedding.length == sherpaEmbedding.length) {
            return sherpaEmbedding;
        }

        LegacySpeakerEmbeddingExtractor.EmbeddingAccumulator accumulator =
                new LegacySpeakerEmbeddingExtractor.EmbeddingAccumulator();
        int sampleCount = Math.min(EMBEDDING_SAMPLE_FRAMES, speechWindowFrames.size());
        if (sampleCount == 0) {
            return legacyExtractor.buildEmbedding(accumulator);
        }
        for (int index = 0; index < sampleCount; index++) {
            int frameIndex = sampleCount == 1
                    ? speechWindowFrames.size() - 1
                    : Math.round(index * (speechWindowFrames.size() - 1.0f) / (sampleCount - 1.0f));
            accumulator.add(legacyExtractor.extractFrameFeatures(speechWindowFrames.get(frameIndex)));
        }
        return legacyExtractor.buildEmbedding(accumulator);
    }

    private SpeakerRole classifyEmbedding(
            float instructorSimilarity,
            float studentSimilarity,
            float[] modelFeatures,
            boolean strongSpeech,
            boolean overlapEnergyJump,
            boolean overlapBandShift,
            boolean similarityUnstable,
            boolean sherpaEmbedding
    ) {
        if (!sherpaEmbedding && speakerRoleModel != null) {
            return speakerRoleModel.predict(modelFeatures);
        }

        float instructorEnterThreshold = sherpaEmbedding
                ? SHERPA_INSTRUCTOR_ENTER_THRESHOLD
                : INSTRUCTOR_ENTER_THRESHOLD;
        float instructorStayThreshold = sherpaEmbedding
                ? SHERPA_INSTRUCTOR_STAY_THRESHOLD
                : INSTRUCTOR_STAY_THRESHOLD;
        float studentClusterThreshold = sherpaEmbedding
                ? SHERPA_STUDENT_CLUSTER_THRESHOLD
                : STUDENT_CLUSTER_THRESHOLD;
        float bothInstructorThreshold = sherpaEmbedding
                ? SHERPA_BOTH_MIN_INSTRUCTOR_SIMILARITY
                : BOTH_MIN_INSTRUCTOR_SIMILARITY;
        float bothStudentThreshold = sherpaEmbedding
                ? SHERPA_BOTH_MIN_STUDENT_SIMILARITY
                : BOTH_MIN_STUDENT_SIMILARITY;
        boolean knownStudent = studentSimilarity >= studentClusterThreshold;
        boolean instructorDominant = instructorSimilarity >= instructorEnterThreshold
                && instructorSimilarity >= studentSimilarity + SPEAKER_MARGIN;
        boolean instructorSticky = lastStableSpeechRole == SpeakerRole.INSTRUCTOR
                && instructorSimilarity >= instructorStayThreshold
                && instructorSimilarity >= studentSimilarity;
        boolean overlapCandidate = strongSpeech
                && instructorSimilarity >= bothInstructorThreshold
                && studentSimilarity >= bothStudentThreshold
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

        if (rawRole == SpeakerRole.BOTH || pendingRoleFrames >= ROLE_SMOOTHING_WINDOWS) {
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
            float similarity = legacyExtractor.cosineSimilarity(embedding, studentPrototypes.get(index).centroid);
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
        float updateThreshold = isSherpaEmbedding(embedding)
                ? SHERPA_STUDENT_CLUSTER_UPDATE_THRESHOLD
                : STUDENT_CLUSTER_UPDATE_THRESHOLD;
        if (studentMatch.index >= 0 && studentMatch.similarity >= updateThreshold) {
            studentPrototypes.get(studentMatch.index).update(embedding);
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

    @Override
    public SpeakerRole[] processAll(short[] samples) {
        // Fallback to frame-by-frame classification for existing classifier
        int frameSize = 160; // Default frame size
        int frameCount = samples.length / frameSize;
        SpeakerRole[] roles = new SpeakerRole[frameCount];
        for (int i = 0; i < frameCount; i++) {
            short[] frame = new short[frameSize];
            int copyLength = Math.min(frameSize, samples.length - i * frameSize);
            System.arraycopy(samples, i * frameSize, frame, 0, copyLength);
            roles[i] = classify(frame, null);
        }
        return roles;
    }

    @Override
    public void close() {
        if (sherpaExtractor != null) {
            sherpaExtractor.release();
            sherpaExtractor = null;
        }
    }

    private boolean isSherpaEmbedding(float[] embedding) {
        return embedding != null
                && sherpaInstructorEmbedding != null
                && embedding.length > 0
                && embedding.length == sherpaInstructorEmbedding.length;
    }

    private void initSherpa(android.content.Context context) {
        if (context == null || instructorVoiceProfile == null) {
            return;
        }
        try {
            String modelPath = copyAssetToFile(context, "sherpa-onnx/embedding.onnx");
            SpeakerEmbeddingExtractorConfig config = SpeakerEmbeddingExtractorConfig.builder()
                    .setModel(modelPath)
                    .setNumThreads(4)
                    .setDebug(false)
                    .build();
            sherpaExtractor = new SpeakerEmbeddingExtractor(config);
            File profileAudioFile = SessionAudioFileManager.resolveAudioFile(
                    context,
                    instructorVoiceProfile.getAudioFileName()
            );
            sherpaInstructorEmbedding = buildSherpaEmbedding(readPcm16Wav(profileAudioFile));
            if (sherpaInstructorEmbedding.length > 0) {
                Logger.i(TAG, "Sherpa-ONNX speaker role mapping initialized.");
            } else {
                Logger.e(TAG, "Sherpa-ONNX instructor embedding unavailable; using legacy speaker mapping.");
            }
        } catch (Exception exception) {
            Logger.e(TAG, "Failed to init Sherpa-ONNX speaker role mapping.", exception);
            sherpaInstructorEmbedding = new float[0];
            if (sherpaExtractor != null) {
                sherpaExtractor.release();
                sherpaExtractor = null;
            }
        }
    }

    private float[] buildSherpaEmbedding(short[] samples) {
        if (sherpaExtractor == null || samples == null || samples.length == 0) {
            return new float[0];
        }
        OnlineStream stream = null;
        try {
            stream = sherpaExtractor.createStream();
            stream.acceptWaveform(shortToFloat(samples), 16000);
            stream.inputFinished();
            if (!sherpaExtractor.isReady(stream)) {
                return new float[0];
            }
            float[] embedding = sherpaExtractor.compute(stream);
            return LegacySpeakerEmbeddingExtractor.l2Normalize(embedding);
        } catch (RuntimeException exception) {
            Logger.e(TAG, "Failed to compute Sherpa speaker embedding.", exception);
            return new float[0];
        } finally {
            if (stream != null) {
                stream.release();
            }
        }
    }

    private float[] shortToFloat(short[] samples) {
        float[] floatSamples = new float[samples.length];
        for (int index = 0; index < samples.length; index++) {
            floatSamples[index] = samples[index] / 32768.0f;
        }
        return floatSamples;
    }

    private short[] readPcm16Wav(File file) throws IOException {
        if (file == null || !file.exists() || file.length() <= 44L) {
            return new short[0];
        }
        try (RandomAccessFile randomAccessFile = new RandomAccessFile(file, "r")) {
            if (randomAccessFile.length() <= 44L) {
                return new short[0];
            }
            randomAccessFile.seek(40L);
            int dataBytes = Integer.reverseBytes(randomAccessFile.readInt());
            int safeDataBytes = (int) Math.min(Math.max(0L, randomAccessFile.length() - 44L), dataBytes);
            safeDataBytes -= safeDataBytes % 2;
            short[] samples = new short[safeDataBytes / 2];
            randomAccessFile.seek(44L);
            for (int index = 0; index < samples.length; index++) {
                int low = randomAccessFile.read();
                int high = randomAccessFile.read();
                if (low < 0 || high < 0) {
                    break;
                }
                samples[index] = (short) ((high << 8) | low);
            }
            return samples;
        }
    }

    private String copyAssetToFile(android.content.Context context, String assetPath) {
        File file = new File(context.getFilesDir(), assetPath);
        if (file.exists()) {
            return file.getAbsolutePath();
        }
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        try (java.io.InputStream in = context.getAssets().open(assetPath);
             java.io.OutputStream out = new java.io.FileOutputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return file.getAbsolutePath();
        } catch (Exception exception) {
            Logger.e(TAG, "Failed to copy Sherpa asset: " + assetPath, exception);
            return "";
        }
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

        void update(float[] embedding) {
            int updateWeight = Math.min(frameCount, MIN_STUDENT_CLUSTER_FRAMES);
            for (int index = 0; index < centroid.length; index++) {
                centroid[index] = ((centroid[index] * updateWeight) + embedding[index]) / (updateWeight + 1);
            }
            centroid = LegacySpeakerEmbeddingExtractor.l2Normalize(centroid);
            frameCount++;
        }
    }
}
