package com.example.audio.eval;

import android.content.Context;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.audio.audio.AudioConfig;
import com.example.audio.data.WavSessionRecorder;
import com.example.audio.data.InstructorVoiceProfile;
import com.example.audio.data.InstructorVoiceProfileStats;
import com.example.audio.data.InstructorVoiceProfileStore;
import com.example.audio.data.SpeakerRole;
import com.example.audio.disturbance.EnergySpikeDetector;
import com.example.audio.pipeline.AudioPipelineCoordinator;
import com.example.audio.pipeline.FrameAnalysisResult;
import com.example.audio.reverb.EnergyDecayReverbEstimator;
import com.example.audio.speaker.SpeakerEmbeddingExtractor;
import com.example.audio.speaker.SpeakerRoleClassifier;
import com.example.audio.speaker.SpeakerRoleModel;
import com.example.audio.vad.SpeechDetectorFactory;
import com.example.audio.vad.VadResult;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class BatchEvaluationInstrumentedTest {

    private static final String TAG = "BatchEval";
    private static final String ROOT_DIR_NAME = "DeployTeachEval";
    private static final String PROFILE_CACHE_FILE_NAME = "instructor_profile_cache.json";

    @Test
    public void runBatchEvaluationIfDatasetPresent() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File rootDir = resolveRootDir(context);
        File audioDir = new File(rootDir, "audio");
        if (!audioDir.exists()) {
            Log.i(TAG, "No eval dataset found at " + audioDir.getAbsolutePath() + "; skipping.");
            return;
        }

        File[] wavFiles = audioDir.listFiles((dir, name) ->
                !name.startsWith("._") && name.toLowerCase().endsWith(".wav")
        );
        if (wavFiles == null || wavFiles.length == 0) {
            Log.i(TAG, "No WAV files found at " + audioDir.getAbsolutePath() + "; skipping.");
            return;
        }
        Arrays.sort(wavFiles, Comparator.comparing(File::getName));

        File predictionDir = new File(rootDir, "predictions");
        if (!predictionDir.exists() && !predictionDir.mkdirs()) {
            throw new IllegalStateException("Failed to create " + predictionDir.getAbsolutePath());
        }
        File vadDir = new File(rootDir, "vad");
        if (!vadDir.exists() && !vadDir.mkdirs()) {
            throw new IllegalStateException("Failed to create " + vadDir.getAbsolutePath());
        }
        File filteredAudioDir = new File(rootDir, "filtered_audio");
        if (!filteredAudioDir.exists() && !filteredAudioDir.mkdirs()) {
            throw new IllegalStateException("Failed to create " + filteredAudioDir.getAbsolutePath());
        }

        InstructorVoiceProfile profile = resolveInstructorProfile(context, rootDir);
        for (File wavFile : wavFiles) {
            File predictionFile = new File(predictionDir, replaceExtension(wavFile.getName(), ".json"));
            File vadFile = new File(vadDir, replaceExtension(wavFile.getName(), ".json"));
            File filteredAudioFile = new File(filteredAudioDir, wavFile.getName());
            processOneFile(context, profile, wavFile, predictionFile, vadFile, filteredAudioFile);
            Log.i(TAG, "Wrote prediction " + predictionFile.getAbsolutePath());
            Log.i(TAG, "Wrote Android VAD " + vadFile.getAbsolutePath());
            Log.i(TAG, "Wrote conditioned audio " + filteredAudioFile.getAbsolutePath());
        }
    }

    private File resolveRootDir(Context context) {
        File internalRoot = new File(context.getFilesDir(), ROOT_DIR_NAME);
        File internalAudioDir = new File(internalRoot, "audio");
        if (internalAudioDir.exists()) {
            Log.i(TAG, "Using internal eval root " + internalRoot.getAbsolutePath());
            return internalRoot;
        }

        File externalRoot = new File(context.getExternalFilesDir(null), ROOT_DIR_NAME);
        File externalAudioDir = new File(externalRoot, "audio");
        if (externalAudioDir.exists()) {
            Log.i(TAG, "Using external eval root " + externalRoot.getAbsolutePath());
            return externalRoot;
        }

        Log.i(
                TAG,
                "No eval audio dir at internal="
                        + internalAudioDir.getAbsolutePath()
                        + " or external="
                        + externalAudioDir.getAbsolutePath()
        );
        return internalRoot;
    }

    private InstructorVoiceProfile resolveInstructorProfile(Context context, File rootDir) throws Exception {
        File profileDir = new File(rootDir, "profile");
        File profileAudioFile = new File(profileDir, "instructor_profile.wav");
        if (!profileAudioFile.exists()) {
            InstructorVoiceProfile storedProfile = InstructorVoiceProfileStore.getInstance().readProfile(context);
            if (storedProfile != null) {
                Log.i(TAG, "Using app-stored instructor profile because no eval profile WAV exists.");
                return storedProfile;
            }
            Log.w(TAG, "No instructor profile found. Speaker role evaluation will classify speech without enrollment.");
            return null;
        }

        File profileCacheFile = new File(profileDir, PROFILE_CACHE_FILE_NAME);
        InstructorVoiceProfile cachedProfile = readCachedInstructorProfile(profileCacheFile, profileAudioFile);
        if (cachedProfile != null) {
            Log.i(TAG, "Loaded cached eval instructor profile from " + profileCacheFile.getAbsolutePath());
            return cachedProfile;
        }

        PcmWav profileWav = PcmWav.read(profileAudioFile);
        AudioConfig config = SpeechDetectorFactory.activeAudioConfig();
        InstructorVoiceProfileStats stats = new InstructorVoiceProfileStats();
        for (int offset = 0; offset < profileWav.samples.length; offset += config.getFrameSizeSamples()) {
            short[] frame = new short[config.getFrameSizeSamples()];
            int copyLength = Math.min(config.getFrameSizeSamples(), profileWav.samples.length - offset);
            System.arraycopy(profileWav.samples, offset, frame, 0, copyLength);
            stats.addFrame(frame);
        }
        Log.i(TAG, "Built eval instructor profile from " + profileAudioFile.getAbsolutePath());
        InstructorVoiceProfile profile = new InstructorVoiceProfile(
                "eval-instructor-profile",
                "Eval Instructor Profile",
                PROFILE_CACHE_FILE_NAME,
                profileAudioFile.getName(),
                "Evaluation profile audio",
                0L,
                profileWav.durationMillis(),
                profileWav.durationMillis(),
                profileWav.sampleRateHz,
                1,
                config.getFrameSizeSamples(),
                stats.getFrameCount(),
                profileAudioFile.length(),
                stats.getAverageRms(),
                stats.getAverageZcr(),
                stats.getAverageLowBandRatio(),
                stats.getAverageHighBandRatio(),
                stats.getEmbeddingVersion(),
                stats.getEmbedding()
        );
        writeCachedInstructorProfile(profileCacheFile, profile);
        return profile;
    }

    private InstructorVoiceProfile readCachedInstructorProfile(File cacheFile, File profileAudioFile) {
        if (!cacheFile.exists()) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(cacheFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
            InstructorVoiceProfile profile = InstructorVoiceProfile.fromJson(new JSONObject(builder.toString()));
            if (!profileAudioFile.getName().equals(profile.getAudioFileName())) {
                Log.i(TAG, "Ignoring eval profile cache because audio filename changed.");
                return null;
            }
            if (profile.getAudioFileSizeBytes() != profileAudioFile.length()) {
                Log.i(TAG, "Ignoring eval profile cache because audio file size changed.");
                return null;
            }
            if (profile.getEmbeddingVersion() != SpeakerEmbeddingExtractor.EMBEDDING_VERSION
                    || profile.getSpeakerEmbedding().length != new SpeakerEmbeddingExtractor().embeddingSize()) {
                Log.i(TAG, "Ignoring eval profile cache because embedding format changed.");
                return null;
            }
            return profile;
        } catch (Exception exception) {
            Log.w(TAG, "Failed to read eval instructor profile cache; rebuilding.", exception);
            return null;
        }
    }

    private void writeCachedInstructorProfile(File cacheFile, InstructorVoiceProfile profile) throws Exception {
        File parent = cacheFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("Failed to create " + parent.getAbsolutePath());
        }
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(cacheFile, false))) {
            writer.write(profile.toJson().toString(2));
        }
        Log.i(TAG, "Wrote cached eval instructor profile to " + cacheFile.getAbsolutePath());
    }

    private void processOneFile(
            Context context,
            InstructorVoiceProfile profile,
            File wavFile,
            File predictionFile,
            File vadFile,
            File filteredAudioFile
    ) throws Exception {
        PcmWav wav = PcmWav.read(wavFile);
        AudioConfig config = SpeechDetectorFactory.activeAudioConfig();
        if (wav.sampleRateHz != config.getSampleRateHz()) {
            throw new IllegalArgumentException(wavFile.getName() + " must be 16 kHz.");
        }

        AudioPipelineCoordinator coordinator = new AudioPipelineCoordinator(
                SpeechDetectorFactory.create(context),
                new EnergySpikeDetector(),
                new EnergyDecayReverbEstimator(),
                new SpeakerRoleClassifier(profile, SpeakerRoleModel.load(context))
        );
        try {
            List<RoleFrame> frames = analyzeFrames(coordinator, wav.samples, config, filteredAudioFile);
            writePrediction(predictionFile, wavFile.getName(), wav.durationMillis(), config.getFrameDurationMs(), frames);
            writeVadExport(
                    vadFile,
                    wavFile.getName(),
                    filteredAudioFile.getName(),
                    wav.durationMillis(),
                    config.getFrameDurationMs(),
                    frames
            );
        } finally {
            coordinator.close();
        }
    }

    private List<RoleFrame> analyzeFrames(
            AudioPipelineCoordinator coordinator,
            short[] samples,
            AudioConfig config,
            File filteredAudioFile
    ) throws Exception {
        List<RoleFrame> frames = new ArrayList<>();
        int frameSize = config.getFrameSizeSamples();
        int frameDurationMs = config.getFrameDurationMs();
        WavSessionRecorder filteredRecorder = new WavSessionRecorder(filteredAudioFile, config);
        try {
            for (int offset = 0; offset < samples.length; offset += frameSize) {
                short[] frame = new short[frameSize];
                int copyLength = Math.min(frameSize, samples.length - offset);
                System.arraycopy(samples, offset, frame, 0, copyLength);
                long timestampMillis = ((long) frames.size()) * frameDurationMs;
                FrameAnalysisResult result = coordinator.process(frame, timestampMillis);
                VadResult vadResult = result.getVadResult();
                SpeakerRole role = vadResult == null ? SpeakerRole.SILENCE : vadResult.getSpeakerRole();
                boolean speech = vadResult != null && vadResult.isSpeech();
                float confidence = vadResult == null || vadResult.getConfidence() == null
                        ? 0.0f
                        : vadResult.getConfidence();
                short[] conditionedFrame = vadResult == null || vadResult.getConditionedFrame() == null
                        ? frame
                        : vadResult.getConditionedFrame();
                filteredRecorder.writeFrame(conditionedFrame);
                frames.add(new RoleFrame(timestampMillis, role, speech, confidence));
            }
            filteredRecorder.finish();
        } catch (Exception exception) {
            filteredRecorder.abort();
            throw exception;
        }
        return frames;
    }

    private void writePrediction(
            File predictionFile,
            String wavFileName,
            long durationMillis,
            int frameDurationMs,
            List<RoleFrame> frames
    ) throws Exception {
        JSONObject root = new JSONObject();
        root.put("id", replaceExtension(wavFileName, ""));
        root.put("audioFile", "audio/" + wavFileName);
        root.put("durationMillis", durationMillis);
        root.put("sampleRateHz", AudioConfig.DEFAULT_SAMPLE_RATE_HZ);
        root.put("roleIntervals", buildRoleIntervals(frames, durationMillis, frameDurationMs));
        root.put("frames", buildFrameDiagnostics(frames, frameDurationMs));

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(predictionFile, false))) {
            writer.write(root.toString(2));
        }
    }

    private void writeVadExport(
            File vadFile,
            String wavFileName,
            String filteredAudioFileName,
            long durationMillis,
            int frameDurationMs,
            List<RoleFrame> frames
    ) throws Exception {
        JSONObject root = new JSONObject();
        root.put("id", replaceExtension(wavFileName, ""));
        root.put("audioFile", "audio/" + wavFileName);
        root.put("conditionedAudioFile", "filtered_audio/" + filteredAudioFileName);
        root.put("durationMillis", durationMillis);
        root.put("sampleRateHz", AudioConfig.DEFAULT_SAMPLE_RATE_HZ);
        root.put("vadEngine", SpeechDetectorFactory.activeDetectorName());
        root.put("speechIntervals", buildSpeechIntervals(frames, durationMillis, frameDurationMs));
        root.put("frames", buildFrameDiagnostics(frames, frameDurationMs));

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(vadFile, false))) {
            writer.write(root.toString(2));
        }
    }

    private JSONArray buildSpeechIntervals(List<RoleFrame> frames, long durationMillis, int frameDurationMs)
            throws Exception {
        JSONArray intervals = new JSONArray();
        boolean inSpeech = false;
        long startMs = 0L;
        for (int index = 0; index < frames.size(); index++) {
            RoleFrame frame = frames.get(index);
            if (frame.speech && !inSpeech) {
                inSpeech = true;
                startMs = frame.timestampMillis;
            } else if (!frame.speech && inSpeech) {
                inSpeech = false;
                intervals.put(toSpeechInterval(startMs, Math.min(durationMillis, ((long) index) * frameDurationMs)));
            }
        }
        if (inSpeech) {
            intervals.put(toSpeechInterval(startMs, durationMillis));
        }
        return intervals;
    }

    private JSONObject toSpeechInterval(long startMs, long endMs) throws Exception {
        JSONObject interval = new JSONObject();
        interval.put("startOffsetMillis", startMs);
        interval.put("endOffsetMillis", endMs);
        return interval;
    }

    private JSONArray buildRoleIntervals(List<RoleFrame> frames, long durationMillis, int frameDurationMs) throws Exception {
        JSONArray intervals = new JSONArray();
        if (frames.isEmpty()) {
            return intervals;
        }

        SpeakerRole currentRole = frames.get(0).role;
        long startMs = 0L;
        for (int index = 1; index < frames.size(); index++) {
            SpeakerRole nextRole = frames.get(index).role;
            if (nextRole == currentRole) {
                continue;
            }
            long endMs = Math.min(durationMillis, ((long) index) * frameDurationMs);
            intervals.put(toInterval(currentRole, startMs, endMs));
            startMs = endMs;
            currentRole = nextRole;
        }
        intervals.put(toInterval(currentRole, startMs, durationMillis));
        return intervals;
    }

    private JSONObject toInterval(SpeakerRole role, long startMs, long endMs) throws Exception {
        JSONObject interval = new JSONObject();
        interval.put("role", role.name());
        interval.put("startOffsetMillis", startMs);
        interval.put("endOffsetMillis", endMs);
        return interval;
    }

    private JSONArray buildFrameDiagnostics(List<RoleFrame> frames, int frameDurationMs) throws Exception {
        JSONArray diagnostics = new JSONArray();
        for (int index = 0; index < frames.size(); index++) {
            RoleFrame frame = frames.get(index);
            JSONObject item = new JSONObject();
            item.put("startOffsetMillis", frame.timestampMillis);
            item.put("endOffsetMillis", frame.timestampMillis + frameDurationMs);
            item.put("role", frame.role.name());
            item.put("speech", frame.speech);
            item.put("confidence", frame.confidence);
            diagnostics.put(item);
        }
        return diagnostics;
    }

    private String replaceExtension(String fileName, String replacement) {
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0) {
            return fileName + replacement;
        }
        return fileName.substring(0, dotIndex) + replacement;
    }

    private static final class RoleFrame {
        private final long timestampMillis;
        private final SpeakerRole role;
        private final boolean speech;
        private final float confidence;

        private RoleFrame(long timestampMillis, SpeakerRole role, boolean speech, float confidence) {
            this.timestampMillis = timestampMillis;
            this.role = role == null ? SpeakerRole.SILENCE : role;
            this.speech = speech;
            this.confidence = confidence;
        }
    }

    private static final class PcmWav {
        private final int sampleRateHz;
        private final short[] samples;

        private PcmWav(int sampleRateHz, short[] samples) {
            this.sampleRateHz = sampleRateHz;
            this.samples = samples;
        }

        private long durationMillis() {
            return (samples.length * 1000L) / sampleRateHz;
        }

        private static PcmWav read(File file) throws Exception {
            byte[] data;
            try (BufferedInputStream inputStream = new BufferedInputStream(new FileInputStream(file))) {
                data = new byte[(int) file.length()];
                int read = inputStream.read(data);
                if (read != data.length) {
                    throw new IllegalArgumentException("Could not read full WAV: " + file.getAbsolutePath());
                }
            }

            ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            if (buffer.getInt(0) != 0x46464952 || buffer.getInt(8) != 0x45564157) {
                throw new IllegalArgumentException("Not a RIFF/WAVE file: " + file.getName());
            }

            int cursor = 12;
            Integer sampleRateHz = null;
            Integer channelCount = null;
            Integer bitsPerSample = null;
            int dataOffset = -1;
            int dataSize = -1;

            while (cursor + 8 <= data.length) {
                int chunkId = buffer.getInt(cursor);
                int chunkSize = buffer.getInt(cursor + 4);
                int chunkDataOffset = cursor + 8;
                if (chunkId == 0x20746d66) {
                    int audioFormat = buffer.getShort(chunkDataOffset) & 0xffff;
                    channelCount = buffer.getShort(chunkDataOffset + 2) & 0xffff;
                    sampleRateHz = buffer.getInt(chunkDataOffset + 4);
                    bitsPerSample = buffer.getShort(chunkDataOffset + 14) & 0xffff;
                    if (audioFormat != 1) {
                        throw new IllegalArgumentException("Only PCM WAV is supported: " + file.getName());
                    }
                } else if (chunkId == 0x61746164) {
                    dataOffset = chunkDataOffset;
                    dataSize = chunkSize;
                    break;
                }
                cursor = chunkDataOffset + chunkSize + (chunkSize % 2);
            }

            if (sampleRateHz == null || channelCount == null || bitsPerSample == null || dataOffset < 0) {
                throw new IllegalArgumentException("Missing WAV fmt/data chunk: " + file.getName());
            }
            if (sampleRateHz != AudioConfig.DEFAULT_SAMPLE_RATE_HZ || channelCount != 1 || bitsPerSample != 16) {
                throw new IllegalArgumentException("WAV must be 16 kHz mono PCM16: " + file.getName());
            }

            int sampleCount = dataSize / 2;
            short[] samples = new short[sampleCount];
            ByteBuffer pcm = ByteBuffer.wrap(data, dataOffset, dataSize).order(ByteOrder.LITTLE_ENDIAN);
            for (int index = 0; index < sampleCount; index++) {
                samples[index] = pcm.getShort();
            }
            return new PcmWav(sampleRateHz, samples);
        }
    }
}
