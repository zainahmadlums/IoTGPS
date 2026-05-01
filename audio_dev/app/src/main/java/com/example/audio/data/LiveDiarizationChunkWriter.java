package com.example.audio.data;

import android.content.Context;

import com.example.audio.audio.AudioConfig;
import com.example.audio.util.Logger;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import org.json.JSONArray;

public final class LiveDiarizationChunkWriter {

    private static final String TAG = "LiveDiarizationChunks";
    private static final long DEFAULT_CHUNK_DURATION_MILLIS = 40_000L;
    private static final long DEFAULT_OVERLAP_MILLIS = 5_000L;

    private final Context context;
    private final AudioConfig audioConfig;
    private final long sessionStartTimeMillis;
    private final long chunkDurationMillis;
    private final long overlapMillis;
    private final List<SessionDiarizationChunk> chunks = new ArrayList<>();
    private final List<BufferedFrame> overlapFrames = new ArrayList<>();

    private WavSessionRecorder recorder;
    private File currentFile;
    private File currentMetadataFile;
    private long currentChunkStartOffsetMillis = -1L;
    private int chunkIndex;
    private final List<LabelFrame> currentFrames = new ArrayList<>();

    public LiveDiarizationChunkWriter(Context context, AudioConfig audioConfig, long sessionStartTimeMillis) {
        this(context, audioConfig, sessionStartTimeMillis, DEFAULT_CHUNK_DURATION_MILLIS, DEFAULT_OVERLAP_MILLIS);
    }

    LiveDiarizationChunkWriter(
            Context context,
            AudioConfig audioConfig,
            long sessionStartTimeMillis,
            long chunkDurationMillis,
            long overlapMillis
    ) {
        this.context = context.getApplicationContext();
        this.audioConfig = audioConfig;
        this.sessionStartTimeMillis = sessionStartTimeMillis;
        this.chunkDurationMillis = chunkDurationMillis;
        this.overlapMillis = overlapMillis;
    }

    public synchronized void writeFrame(
            short[] conditionedFrame,
            long timestampMillis,
            boolean speech,
            SpeakerRole role
    ) throws IOException {
        if (conditionedFrame == null || conditionedFrame.length == 0) {
            return;
        }
        long offsetMillis = Math.max(0L, timestampMillis - sessionStartTimeMillis);
        if (recorder == null || offsetMillis >= currentChunkStartOffsetMillis + chunkDurationMillis) {
            rotate(offsetMillis);
        }
        recorder.writeFrame(conditionedFrame);
        currentFrames.add(new LabelFrame(offsetMillis, speech, role == null ? SpeakerRole.SILENCE : role));
        rememberOverlapFrame(conditionedFrame, offsetMillis);
    }

    public synchronized List<SessionDiarizationChunk> finish(long sessionEndTimeMillis) {
        closeCurrent(Math.max(0L, sessionEndTimeMillis - sessionStartTimeMillis));
        return Collections.unmodifiableList(new ArrayList<>(chunks));
    }

    public synchronized void abort() {
        if (recorder != null) {
            recorder.abort();
            recorder = null;
        }
        currentFile = null;
        currentMetadataFile = null;
        currentChunkStartOffsetMillis = -1L;
        overlapFrames.clear();
        currentFrames.clear();
    }

    private void rotate(long offsetMillis) throws IOException {
        closeCurrent(offsetMillis);
        currentChunkStartOffsetMillis = chunks.isEmpty()
                ? offsetMillis
                : Math.max(0L, offsetMillis - overlapMillis);
        currentFile = new File(
                SessionAudioFileManager.getAudioDirectory(context),
                buildChunkFileName(sessionStartTimeMillis, ++chunkIndex)
        );
        currentMetadataFile = new File(
                SessionAudioFileManager.getAudioDirectory(context),
                buildChunkMetadataFileName(sessionStartTimeMillis, chunkIndex)
        );
        currentFrames.clear();
        recorder = new WavSessionRecorder(currentFile, audioConfig);
        for (BufferedFrame bufferedFrame : overlapFrames) {
            if (bufferedFrame.offsetMillis >= currentChunkStartOffsetMillis) {
                recorder.writeFrame(bufferedFrame.samples);
                currentFrames.add(new LabelFrame(bufferedFrame.offsetMillis, bufferedFrame.speech, bufferedFrame.role));
            }
        }
    }

    private void closeCurrent(long endOffsetMillis) {
        if (recorder == null) {
            return;
        }
        try {
            recorder.finish();
            long safeEndOffsetMillis = Math.max(currentChunkStartOffsetMillis, endOffsetMillis);
            writeChunkMetadata(safeEndOffsetMillis);
            chunks.add(new SessionDiarizationChunk(
                    currentFile != null ? currentFile.getName() : null,
                    currentMetadataFile != null ? currentMetadataFile.getName() : null,
                    currentChunkStartOffsetMillis,
                    safeEndOffsetMillis
            ));
        } catch (IOException ioException) {
            Logger.e(TAG, "Failed to finalize diarization chunk.", ioException);
        } finally {
            recorder = null;
            currentFile = null;
            currentMetadataFile = null;
            currentChunkStartOffsetMillis = -1L;
            currentFrames.clear();
        }
    }

    private static String buildChunkFileName(long sessionStartTimeMillis, int chunkIndex) {
        return String.format(
                Locale.US,
                "deployteach_diarization_chunk_%1$tY%1$tm%1$td_%1$tH%1$tM%1$tS_%2$04d.wav",
                sessionStartTimeMillis,
                chunkIndex
        );
    }

    private static String buildChunkMetadataFileName(long sessionStartTimeMillis, int chunkIndex) {
        return String.format(
                Locale.US,
                "deployteach_chunk_metadata_%1$tY%1$tm%1$td_%1$tH%1$tM%1$tS_%2$04d.json",
                sessionStartTimeMillis,
                chunkIndex
        );
    }

    private void writeChunkMetadata(long chunkEndOffsetMillis) throws IOException {
        if (currentMetadataFile == null) {
            return;
        }
        try {
            JSONObject root = new JSONObject();
            root.put("session_id", "session-" + sessionStartTimeMillis);
            root.put("chunk_index", Math.max(0, chunkIndex - 1));
            root.put("chunk_start_ms", currentChunkStartOffsetMillis);
            root.put("chunk_end_ms", chunkEndOffsetMillis);
            root.put("sample_rate", audioConfig.getSampleRateHz());
            root.put("vad_segments", buildSpeechSegments());
            root.put("live_label_segments", buildLabelSegments());
            try (FileWriter writer = new FileWriter(currentMetadataFile, false)) {
                writer.write(root.toString(2));
            }
        } catch (Exception exception) {
            throw new IOException("Failed to write chunk metadata.", exception);
        }
    }

    private JSONArray buildSpeechSegments() throws Exception {
        JSONArray segments = new JSONArray();
        boolean active = false;
        long startMs = 0L;
        for (LabelFrame frame : currentFrames) {
            long localStart = Math.max(0L, frame.offsetMillis - currentChunkStartOffsetMillis);
            if (frame.speech && !active) {
                active = true;
                startMs = localStart;
            } else if (!frame.speech && active) {
                active = false;
                segments.put(intervalJson(startMs, localStart));
            }
        }
        if (active && !currentFrames.isEmpty()) {
            long endMs = Math.max(0L, currentFrames.get(currentFrames.size() - 1).offsetMillis
                    - currentChunkStartOffsetMillis + audioConfig.getFrameDurationMs());
            segments.put(intervalJson(startMs, endMs));
        }
        return segments;
    }

    private JSONArray buildLabelSegments() throws Exception {
        JSONArray segments = new JSONArray();
        if (currentFrames.isEmpty()) {
            return segments;
        }
        SpeakerRole currentRole = currentFrames.get(0).role;
        long startMs = Math.max(0L, currentFrames.get(0).offsetMillis - currentChunkStartOffsetMillis);
        for (int index = 1; index < currentFrames.size(); index++) {
            LabelFrame frame = currentFrames.get(index);
            if (frame.role == currentRole) {
                continue;
            }
            long localStart = Math.max(0L, frame.offsetMillis - currentChunkStartOffsetMillis);
            JSONObject item = intervalJson(startMs, localStart);
            item.put("label", currentRole.name());
            segments.put(item);
            startMs = localStart;
            currentRole = frame.role;
        }
        long endMs = Math.max(startMs, currentFrames.get(currentFrames.size() - 1).offsetMillis
                - currentChunkStartOffsetMillis + audioConfig.getFrameDurationMs());
        JSONObject item = intervalJson(startMs, endMs);
        item.put("label", currentRole.name());
        segments.put(item);
        return segments;
    }

    private JSONObject intervalJson(long startMs, long endMs) throws Exception {
        JSONObject item = new JSONObject();
        item.put("start_ms", startMs);
        item.put("end_ms", Math.max(startMs, endMs));
        return item;
    }

    private void rememberOverlapFrame(short[] frame, long offsetMillis) {
        SpeakerRole role = currentFrames.isEmpty() ? SpeakerRole.SILENCE : currentFrames.get(currentFrames.size() - 1).role;
        boolean speech = !currentFrames.isEmpty() && currentFrames.get(currentFrames.size() - 1).speech;
        overlapFrames.add(new BufferedFrame(frame, offsetMillis, speech, role));
        long minimumOffsetMillis = Math.max(0L, offsetMillis - overlapMillis);
        while (!overlapFrames.isEmpty() && overlapFrames.get(0).offsetMillis < minimumOffsetMillis) {
            overlapFrames.remove(0);
        }
    }

    private static final class BufferedFrame {
        private final short[] samples;
        private final long offsetMillis;
        private final boolean speech;
        private final SpeakerRole role;

        private BufferedFrame(short[] samples, long offsetMillis, boolean speech, SpeakerRole role) {
            this.samples = samples.clone();
            this.offsetMillis = offsetMillis;
            this.speech = speech;
            this.role = role;
        }
    }

    private static final class LabelFrame {
        private final long offsetMillis;
        private final boolean speech;
        private final SpeakerRole role;

        private LabelFrame(long offsetMillis, boolean speech, SpeakerRole role) {
            this.offsetMillis = offsetMillis;
            this.speech = speech;
            this.role = role;
        }
    }
}
