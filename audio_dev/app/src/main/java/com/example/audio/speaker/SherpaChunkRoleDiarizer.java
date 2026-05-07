package com.example.audio.speaker;

import android.content.Context;

import com.example.audio.data.InstructorVoiceProfile;
import com.example.audio.data.SessionAudioFileManager;
import com.example.audio.data.SessionDiarizationChunk;
import com.example.audio.data.SessionRoleInterval;
import com.example.audio.data.SpeakerRole;
import com.example.audio.util.Logger;
import com.k2fsa.sherpa.onnx.FastClusteringConfig;
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization;
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig;
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationSegment;
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig;
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor;
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig;
import com.example.audio.data.SessionSpeechInterval;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class SherpaChunkRoleDiarizer implements AutoCloseable {

    private static final String TAG = "SherpaChunkRoleDiarizer";
    private static final String SEGMENTATION_MODEL = "sherpa-onnx/segmentation.onnx";
    private static final String EMBEDDING_MODEL = "sherpa-onnx/embedding.onnx";
    private static final int SAMPLE_RATE_HZ = 16_000;
    private static final int BIN_MILLIS = 100;

    private final Context context;
    private final OfflineSpeakerDiarization diarizer;
    private final SpeakerEmbeddingExtractor embeddingExtractor;
    private final float[] instructorEmbedding;

    public SherpaChunkRoleDiarizer(Context context, InstructorVoiceProfile instructorVoiceProfile) {
        this.context = context.getApplicationContext();
        String segmentationPath = copyAssetToFile(this.context, SEGMENTATION_MODEL);
        String embeddingPath = copyAssetToFile(this.context, EMBEDDING_MODEL);
        this.diarizer = new OfflineSpeakerDiarization(OfflineSpeakerDiarizationConfig.builder()
                .setSegmentation(OfflineSpeakerSegmentationModelConfig.builder()
                        .setPyannote(OfflineSpeakerSegmentationPyannoteModelConfig.builder()
                                .setModel(segmentationPath)
                                .build())
                        .setNumThreads(4)
                        .setDebug(false)
                        .build())
                .setEmbedding(SpeakerEmbeddingExtractorConfig.builder()
                        .setModel(embeddingPath)
                        .setNumThreads(4)
                        .setDebug(false)
                        .build())
                .setClustering(FastClusteringConfig.builder()
                        .setNumClusters(-1)
                        .setThreshold(0.5f)
                        .build())
                .build());
        this.embeddingExtractor = new SpeakerEmbeddingExtractor(SpeakerEmbeddingExtractorConfig.builder()
                .setModel(embeddingPath)
                .setNumThreads(4)
                .setDebug(false)
                .build());
        File profileAudioFile = instructorVoiceProfile == null
                ? null
                : SessionAudioFileManager.resolveAudioFile(this.context, instructorVoiceProfile.getAudioFileName());
        this.instructorEmbedding = buildEmbedding(readPcm16Wav(profileAudioFile));
    }

    public List<SessionRoleInterval> diarize(
            List<SessionDiarizationChunk> chunks,
            long sessionDurationMillis
    ) {
        List<SessionRoleInterval> intervals = new ArrayList<>();
        if (chunks == null || chunks.isEmpty() || sessionDurationMillis <= 0L || instructorEmbedding.length == 0) {
            return intervals;
        }

        int binCount = Math.max(1, (int) ((sessionDurationMillis + BIN_MILLIS - 1L) / BIN_MILLIS));
        boolean[] instructorBins = new boolean[binCount];
        boolean[] studentBins = new boolean[binCount];
        boolean[] speechBins = new boolean[binCount];

        for (SessionDiarizationChunk chunk : chunks) {
            diarizeChunk(chunk, sessionDurationMillis, instructorBins, studentBins);
            // In the combined diarize call, we should also build speech bins across all chunks
            // to ensure every speech segment gets a role.
            for (SessionSpeechInterval interval : readChunkSpeechIntervals(chunk)) {
                markBins(speechBins, 
                         chunk.getStartOffsetMillis() + interval.getStartOffsetMillis(), 
                         chunk.getStartOffsetMillis() + interval.getEndOffsetMillis());
            }
        }
        return buildIntervals(instructorBins, studentBins, speechBins, sessionDurationMillis, 0L, false);
    }

    public List<SessionRoleInterval> diarizeChunk(SessionDiarizationChunk chunk) {
        List<SessionRoleInterval> intervals = new ArrayList<>();
        if (chunk == null || chunk.getDurationMillis() <= 0L || instructorEmbedding.length == 0) {
            return intervals;
        }

        int binCount = Math.max(1, (int) ((chunk.getDurationMillis() + BIN_MILLIS - 1L) / BIN_MILLIS));
        boolean[] instructorBins = new boolean[binCount];
        boolean[] studentBins = new boolean[binCount];
        boolean[] speechBins = buildSpeechBins(chunk, binCount);
        diarizeChunkLocal(chunk, instructorBins, studentBins);
        applySpeechMask(instructorBins, studentBins, speechBins);
        return buildIntervals(instructorBins, studentBins, speechBins, chunk.getDurationMillis(), chunk.getStartOffsetMillis(), false);
    }

    public static List<SessionRoleInterval> flattenRoleIntervals(
            List<SessionRoleInterval> sourceIntervals,
            long sessionDurationMillis
    ) {
        List<SessionRoleInterval> intervals = new ArrayList<>();
        if (sourceIntervals == null || sourceIntervals.isEmpty() || sessionDurationMillis <= 0L) {
            return intervals;
        }
        int binCount = Math.max(1, (int) ((sessionDurationMillis + BIN_MILLIS - 1L) / BIN_MILLIS));
        boolean[] instructorBins = new boolean[binCount];
        boolean[] studentBins = new boolean[binCount];
        boolean[] speechBins = new boolean[binCount];
        for (SessionRoleInterval interval : sourceIntervals) {
            if (interval == null || interval.getRole() == SpeakerRole.SILENCE) {
                continue;
            }
            if (interval.getRole() == SpeakerRole.INSTRUCTOR || interval.getRole() == SpeakerRole.BOTH) {
                markBins(instructorBins, interval.getStartOffsetMillis(), interval.getEndOffsetMillis());
            }
            if (interval.getRole() == SpeakerRole.STUDENT || interval.getRole() == SpeakerRole.BOTH) {
                markBins(studentBins, interval.getStartOffsetMillis(), interval.getEndOffsetMillis());
            }
        }
        for (int index = 0; index < binCount; index++) {
            speechBins[index] = instructorBins[index] || studentBins[index];
        }
        return buildIntervals(instructorBins, studentBins, speechBins, sessionDurationMillis, 0L, false);
    }

    private void diarizeChunk(
            SessionDiarizationChunk chunk,
            long sessionDurationMillis,
            boolean[] instructorBins,
            boolean[] studentBins
    ) {
        if (chunk == null || chunk.getAudioFileName() == null) {
            return;
        }
        try {
            short[] samples = readPcm16Wav(SessionAudioFileManager.resolveAudioFile(context, chunk.getAudioFileName()));
            if (samples.length == 0) {
                return;
            }
            OfflineSpeakerDiarizationSegment[] segments = diarizer.process(shortToFloat(samples));
            if (segments == null || segments.length == 0) {
                return;
            }
            int instructorSpeaker = chooseInstructorSpeaker(samples, segments);
            for (OfflineSpeakerDiarizationSegment segment : segments) {
                long globalStartMillis = chunk.getStartOffsetMillis() + secondsToMillis(segment.getStart());
                long globalEndMillis = chunk.getStartOffsetMillis() + secondsToMillis(segment.getEnd());
                if (globalEndMillis <= globalStartMillis) {
                    continue;
                }
                globalStartMillis = Math.max(0L, Math.min(sessionDurationMillis, globalStartMillis));
                globalEndMillis = Math.max(globalStartMillis, Math.min(sessionDurationMillis, globalEndMillis));
                markBins(
                        segment.getSpeaker() == instructorSpeaker ? instructorBins : studentBins,
                        globalStartMillis,
                        globalEndMillis
                );
            }
        } catch (RuntimeException exception) {
            Logger.e(TAG, "Failed to diarize chunk: " + chunk.getAudioFileName(), exception);
        }
    }

    private void diarizeChunkLocal(
            SessionDiarizationChunk chunk,
            boolean[] instructorBins,
            boolean[] studentBins
    ) {
        if (chunk == null || chunk.getAudioFileName() == null) {
            return;
        }
        try {
            short[] samples = readPcm16Wav(SessionAudioFileManager.resolveAudioFile(context, chunk.getAudioFileName()));
            if (samples.length == 0) {
                return;
            }
            OfflineSpeakerDiarizationSegment[] segments = diarizer.process(shortToFloat(samples));
            if (segments == null || segments.length == 0) {
                return;
            }
            int instructorSpeaker = chooseInstructorSpeaker(samples, segments);
            for (OfflineSpeakerDiarizationSegment segment : segments) {
                long localStartMillis = secondsToMillis(segment.getStart());
                long localEndMillis = secondsToMillis(segment.getEnd());
                if (localEndMillis <= localStartMillis) {
                    continue;
                }
                localStartMillis = Math.max(0L, Math.min(chunk.getDurationMillis(), localStartMillis));
                localEndMillis = Math.max(localStartMillis, Math.min(chunk.getDurationMillis(), localEndMillis));
                markBins(
                        segment.getSpeaker() == instructorSpeaker ? instructorBins : studentBins,
                        localStartMillis,
                        localEndMillis
                );
            }
        } catch (RuntimeException exception) {
            Logger.e(TAG, "Failed to diarize local chunk: " + chunk.getAudioFileName(), exception);
        }
    }

    private boolean[] buildSpeechBins(SessionDiarizationChunk chunk, int binCount) {
        boolean[] speechBins = new boolean[binCount];
        for (SessionSpeechInterval interval : readChunkSpeechIntervals(chunk)) {
            markBins(speechBins, interval.getStartOffsetMillis(), interval.getEndOffsetMillis());
        }
        return speechBins;
    }

    private List<SessionSpeechInterval> readChunkSpeechIntervals(SessionDiarizationChunk chunk) {
        List<SessionSpeechInterval> intervals = new ArrayList<>();
        if (chunk == null || chunk.getMetadataFileName() == null) {
            return intervals;
        }
        File metadataFile = SessionAudioFileManager.resolveAudioFile(context, chunk.getMetadataFileName());
        if (!metadataFile.exists()) {
            return intervals;
        }
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(metadataFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
            JSONObject root = new JSONObject(builder.toString());
            JSONArray vadSegments = root.optJSONArray("vad_segments");
            if (vadSegments == null) {
                return intervals;
            }
            for (int index = 0; index < vadSegments.length(); index++) {
                JSONObject item = vadSegments.optJSONObject(index);
                if (item == null) {
                    continue;
                }
                long startMillis = item.optLong("start_ms", 0L);
                long endMillis = item.optLong("end_ms", startMillis);
                if (endMillis > startMillis) {
                    intervals.add(new SessionSpeechInterval(startMillis, endMillis, endMillis - startMillis));
                }
            }
        } catch (Exception exception) {
            Logger.e(TAG, "Failed to read chunk VAD metadata: " + chunk.getMetadataFileName(), exception);
        }
        return intervals;
    }

    private void applySpeechMask(
            boolean[] instructorBins,
            boolean[] studentBins,
            boolean[] speechBins
    ) {
        int count = Math.min(speechBins.length, Math.min(instructorBins.length, studentBins.length));
        for (int index = 0; index < count; index++) {
            if (!speechBins[index]) {
                instructorBins[index] = false;
                studentBins[index] = false;
            }
        }
    }

    private int chooseInstructorSpeaker(short[] samples, OfflineSpeakerDiarizationSegment[] segments) {
        Map<Integer, List<OfflineSpeakerDiarizationSegment>> bySpeaker = new HashMap<>();
        for (OfflineSpeakerDiarizationSegment segment : segments) {
            bySpeaker.computeIfAbsent(segment.getSpeaker(), ignored -> new ArrayList<>()).add(segment);
        }

        int bestSpeaker = segments[0].getSpeaker();
        float bestSimilarity = Float.NEGATIVE_INFINITY;
        for (Map.Entry<Integer, List<OfflineSpeakerDiarizationSegment>> entry : bySpeaker.entrySet()) {
            short[] speakerSamples = collectSpeakerSamples(samples, entry.getValue());
            float[] embedding = buildEmbedding(speakerSamples);
            float similarity = LegacySpeakerEmbeddingExtractor.cosineSimilarity(embedding, instructorEmbedding);
            if (similarity > bestSimilarity) {
                bestSimilarity = similarity;
                bestSpeaker = entry.getKey();
            }
        }
        return bestSpeaker;
    }

    private short[] collectSpeakerSamples(short[] samples, List<OfflineSpeakerDiarizationSegment> segments) {
        int totalSamples = 0;
        List<short[]> slices = new ArrayList<>();
        for (OfflineSpeakerDiarizationSegment segment : segments) {
            int startSample = Math.max(0, Math.round(segment.getStart() * SAMPLE_RATE_HZ));
            int endSample = Math.min(samples.length, Math.round(segment.getEnd() * SAMPLE_RATE_HZ));
            if (endSample <= startSample) {
                continue;
            }
            short[] slice = new short[endSample - startSample];
            System.arraycopy(samples, startSample, slice, 0, slice.length);
            slices.add(slice);
            totalSamples += slice.length;
        }
        short[] merged = new short[totalSamples];
        int offset = 0;
        for (short[] slice : slices) {
            System.arraycopy(slice, 0, merged, offset, slice.length);
            offset += slice.length;
        }
        return merged;
    }

    private static void markBins(boolean[] bins, long startMillis, long endMillis) {
        int startBin = Math.max(0, (int) (startMillis / BIN_MILLIS));
        int endBinExclusive = Math.min(bins.length, (int) ((endMillis + BIN_MILLIS - 1L) / BIN_MILLIS));
        for (int index = startBin; index < endBinExclusive; index++) {
            bins[index] = true;
        }
    }

    private static List<SessionRoleInterval> buildIntervals(
            boolean[] instructorBins,
            boolean[] studentBins,
            boolean[] speechBins,
            long durationMillis,
            long offsetMillis,
            boolean includeSilence
    ) {
        List<SessionRoleInterval> intervals = new ArrayList<>();
        SpeakerRole currentRole = null;
        long currentStartMillis = 0L;
        for (int index = 0; index < instructorBins.length; index++) {
            SpeakerRole role = roleForBin(instructorBins[index], studentBins[index], speechBins[index]);
            long startMillis = index * (long) BIN_MILLIS;
            if (currentRole == null) {
                currentRole = role;
                currentStartMillis = startMillis;
                continue;
            }
            if (role != currentRole) {
                addInterval(intervals, currentRole, currentStartMillis, startMillis, durationMillis, offsetMillis, includeSilence);
                currentRole = role;
                currentStartMillis = startMillis;
            }
        }
        addInterval(
                intervals,
                currentRole == null ? SpeakerRole.SILENCE : currentRole,
                currentStartMillis,
                durationMillis,
                durationMillis,
                offsetMillis,
                includeSilence
        );
        return intervals;
    }

    private static SpeakerRole roleForBin(boolean instructor, boolean student, boolean isSpeech) {
        if (!isSpeech) {
            return SpeakerRole.SILENCE;
        }
        if (instructor && student) {
            return SpeakerRole.BOTH;
        }
        // User requested: "it should default to instructor for god sake"
        // If it is speech and not explicitly identified as student, we default to INSTRUCTOR.
        if (student) {
            return SpeakerRole.STUDENT;
        }
        return SpeakerRole.INSTRUCTOR;
    }

    private static void addInterval(
            List<SessionRoleInterval> intervals,
            SpeakerRole role,
            long startMillis,
            long endMillis,
            long durationMillis,
            long offsetMillis,
            boolean includeSilence
    ) {
        long safeStart = Math.max(0L, Math.min(durationMillis, startMillis));
        long safeEnd = Math.max(safeStart, Math.min(durationMillis, endMillis));
        if (safeEnd <= safeStart) {
            return;
        }
        // User requested: "The speech intervals HAVE TO SHOW SOME SPEAKER IT CANNOT SHOW AT ALL COSTS SILENCE"
        // If the role is SILENCE but it was meant to be speech, we should have handled it in roleForBin.
        // Here we just respect includeSilence for actual silence intervals.
        if (!includeSilence && role == SpeakerRole.SILENCE) {
            return;
        }
        intervals.add(new SessionRoleInterval(
                role,
                offsetMillis + safeStart,
                offsetMillis + safeEnd,
                safeEnd - safeStart
        ));
    }

    private float[] buildEmbedding(short[] samples) {
        if (samples == null || samples.length == 0) {
            return new float[0];
        }
        OnlineStream stream = null;
        try {
            stream = embeddingExtractor.createStream();
            stream.acceptWaveform(shortToFloat(samples), SAMPLE_RATE_HZ);
            stream.inputFinished();
            if (!embeddingExtractor.isReady(stream)) {
                return new float[0];
            }
            return LegacySpeakerEmbeddingExtractor.l2Normalize(embeddingExtractor.compute(stream));
        } finally {
            if (stream != null) {
                stream.release();
            }
        }
    }

    private long secondsToMillis(float seconds) {
        return Math.max(0L, Math.round(seconds * 1000.0f));
    }

    private float[] shortToFloat(short[] samples) {
        float[] floatSamples = new float[samples.length];
        for (int index = 0; index < samples.length; index++) {
            floatSamples[index] = samples[index] / 32768.0f;
        }
        return floatSamples;
    }

    private short[] readPcm16Wav(File file) {
        if (file == null || !file.exists() || file.length() <= 44L) {
            return new short[0];
        }
        try (RandomAccessFile randomAccessFile = new RandomAccessFile(file, "r")) {
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
        } catch (IOException exception) {
            Logger.e(TAG, "Failed to read WAV file.", exception);
            return new short[0];
        }
    }

    private String copyAssetToFile(Context context, String assetPath) {
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

    @Override
    public void close() {
        diarizer.release();
        embeddingExtractor.release();
    }
}
