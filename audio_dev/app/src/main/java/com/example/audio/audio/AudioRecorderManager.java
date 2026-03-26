package com.example.audio.audio;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import androidx.core.content.ContextCompat;

import com.example.audio.util.Logger;

import java.util.Arrays;

public class AudioRecorderManager {

    private static final String TAG = "AudioRecorderManager";
    private static final int MAX_CONSECUTIVE_READ_ERRORS = 8;
    private static final long READ_ERROR_BACKOFF_MILLIS = 50L;
    private static final long ZERO_FRAME_RECOVERY_MILLIS = 2000L;
    private static final int PRIMARY_AUDIO_SOURCE = MediaRecorder.AudioSource.VOICE_RECOGNITION;
    private static final int MAX_ZERO_ROUTE_RECOVERIES = 3;
    private static final int DEBUG_READ_LOG_LIMIT = 6;
    private static final int DEBUG_FRAME_LOG_LIMIT = 6;

    public interface FrameCallback {
        void onFrame(short[] frame, long timestampMillis);

        default void onRecorderFailure(String reason, Throwable throwable) {
        }
    }

    private final Context context;
    private final AudioConfig audioConfig;
    private AudioRecord audioRecord;
    private Thread recordingThread;
    private volatile boolean recording;
    private int bufferSizeBytes;
    private int activeAudioSource = -1;
    private int consecutiveZeroRouteRecoveries;

    public AudioRecorderManager(Context context, AudioConfig audioConfig) {
        this.context = context.getApplicationContext();
        this.audioConfig = audioConfig;
    }

    public AudioConfig getAudioConfig() {
        return audioConfig;
    }

    public boolean isRecording() {
        return recording;
    }

    public synchronized void start(FrameCallback frameCallback) {
        if (recording) {
            return;
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            throw new IllegalStateException("RECORD_AUDIO permission is required.");
        }

        audioRecord = createAudioRecord();
        audioRecord.startRecording();

        if (audioRecord.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
            releaseAudioRecord();
            throw new IllegalStateException("AudioRecord failed to enter recording state.");
        }

        recording = true;
        recordingThread = new Thread(() -> readLoop(frameCallback), "audio-record-thread");
        recordingThread.start();
        Logger.d(TAG, "Started recording thread.");
    }

    public synchronized void stop() {
        recording = false;

        if (recordingThread != null) {
            recordingThread.interrupt();
            try {
                recordingThread.join(500L);
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
            }
            recordingThread = null;
        }

        stopAndReleaseAudioRecord();
        Logger.d(TAG, "Stopped recording.");
    }

    public int getBufferSizeBytes() {
        return bufferSizeBytes;
    }

    private AudioRecord createAudioRecord() {
        int minimumBuffer = AudioRecord.getMinBufferSize(
                audioConfig.getSampleRateHz(),
                audioConfig.getChannelConfig(),
                audioConfig.getAudioEncoding()
        );

        if (minimumBuffer <= 0) {
            throw new IllegalStateException("AudioRecord minimum buffer size is invalid.");
        }

        int frameBytes = audioConfig.getFrameSizeBytes();
        bufferSizeBytes = Math.max(minimumBuffer, frameBytes * 4);

        AudioRecord createdRecord = buildAudioRecord(PRIMARY_AUDIO_SOURCE);
        if (createdRecord == null) {
            throw new IllegalStateException("AudioRecord failed to initialize.");
        }

        return createdRecord;
    }

    private void readLoop(FrameCallback frameCallback) {
        short[] chunkBuffer = new short[Math.max(audioConfig.getFrameSizeSamples(), 1024)];
        short[] frameBuffer = new short[audioConfig.getFrameSizeSamples()];
        int frameOffset = 0;
        int consecutiveReadErrors = 0;
        int consecutiveZeroFrames = 0;
        int debugReadLogs = 0;
        int debugFrameLogs = 0;
        int zeroFrameRecoveryThreshold = Math.max(
                32,
                (int) Math.ceil((double) ZERO_FRAME_RECOVERY_MILLIS / audioConfig.getFrameDurationMs())
        );

        try {
            while (recording && !Thread.currentThread().isInterrupted()) {
                AudioRecord activeRecord = audioRecord;
                if (activeRecord == null) {
                    Logger.e(TAG, "AudioRecord became null while read loop was active.");
                    break;
                }

                int samplesRead;
                try {
                    samplesRead = activeRecord.read(chunkBuffer, 0, chunkBuffer.length);
                } catch (RuntimeException runtimeException) {
                    Logger.e(TAG, "AudioRecord.read threw. Attempting recovery.", runtimeException);
                    if (!recoverRecorder(
                            frameCallback,
                            "AudioRecord.read threw",
                            runtimeException
                    )) {
                        return;
                    }
                    frameOffset = 0;
                    consecutiveReadErrors = 0;
                    consecutiveZeroFrames = 0;
                    continue;
                }

                if (samplesRead <= 0) {
                    consecutiveReadErrors++;
                    Logger.e(
                            TAG,
                            "AudioRecord.read returned "
                                    + samplesRead
                                    + " (consecutive="
                                    + consecutiveReadErrors
                                    + ")."
                    );
                    if (shouldRecoverFromReadFailure(samplesRead, consecutiveReadErrors)) {
                        if (!recoverRecorder(
                                frameCallback,
                                "AudioRecord.read returned " + samplesRead,
                                null
                        )) {
                            return;
                        }
                        frameOffset = 0;
                        consecutiveReadErrors = 0;
                        consecutiveZeroFrames = 0;
                    } else {
                        pauseAfterReadFailure();
                    }
                    continue;
                }

                consecutiveReadErrors = 0;
                if (debugReadLogs < DEBUG_READ_LOG_LIMIT) {
                    debugReadLogs++;
                    Logger.d(
                            TAG,
                            "read samples="
                                    + samplesRead
                                    + ", bytes="
                                    + (samplesRead * audioConfig.getBytesPerSample() * audioConfig.getChannelCount())
                                    + ", frameSizeSamples="
                                    + audioConfig.getFrameSizeSamples()
                                    + ", source="
                                    + activeAudioSource
                    );
                }
                int chunkOffset = 0;
                while (chunkOffset < samplesRead && recording) {
                    int copyLength = Math.min(frameBuffer.length - frameOffset, samplesRead - chunkOffset);
                    System.arraycopy(chunkBuffer, chunkOffset, frameBuffer, frameOffset, copyLength);
                    frameOffset += copyLength;
                    chunkOffset += copyLength;

                    if (frameOffset == frameBuffer.length) {
                        if (isAllZeroFrame(frameBuffer)) {
                            consecutiveZeroFrames++;
                            if (consecutiveZeroFrames >= zeroFrameRecoveryThreshold) {
                                Logger.e(
                                        TAG,
                                        "Detected "
                                                + consecutiveZeroFrames
                                                + " consecutive all-zero frames on source="
                                                + activeAudioSource
                                                + ". Recreating AudioRecord."
                                );
                                if (!recoverRecorder(
                                        frameCallback,
                                        "consecutive all-zero frames",
                                        null
                                )) {
                                    return;
                                }
                                frameOffset = 0;
                                consecutiveReadErrors = 0;
                                consecutiveZeroFrames = 0;
                                break;
                            }
                        } else {
                            consecutiveZeroFrames = 0;
                        }

                        try {
                            if (debugFrameLogs < DEBUG_FRAME_LOG_LIMIT) {
                                debugFrameLogs++;
                                Logger.d(
                                        TAG,
                                        "dispatch frame samples="
                                                + frameBuffer.length
                                                + ", bytes="
                                                + audioConfig.getFrameSizeBytes()
                                                + ", timestamp="
                                                + System.currentTimeMillis()
                                );
                            }
                            frameCallback.onFrame(
                                    Arrays.copyOf(frameBuffer, frameBuffer.length),
                                    System.currentTimeMillis()
                            );
                        } catch (Throwable throwable) {
                            Logger.e(TAG, "Frame callback threw. Continuing capture.", throwable);
                        }
                        frameOffset = 0;
                    }
                }
            }
        } finally {
            Logger.d(TAG, "Read loop exited. recording=" + recording);
        }
    }

    private boolean shouldRecoverFromReadFailure(int samplesRead, int consecutiveReadErrors) {
        return samplesRead == AudioRecord.ERROR_DEAD_OBJECT
                || consecutiveReadErrors >= MAX_CONSECUTIVE_READ_ERRORS;
    }

    private void pauseAfterReadFailure() {
        try {
            Thread.sleep(READ_ERROR_BACKOFF_MILLIS);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean recoverRecorder(
            FrameCallback frameCallback,
            String reason,
            Throwable throwable
    ) {
        synchronized (this) {
            if (!recording) {
                return false;
            }

            Logger.e(TAG, "Recovering AudioRecord after failure: " + reason, throwable);
            stopAndReleaseAudioRecord();

            try {
                if ("consecutive all-zero frames".equals(reason)) {
                    consecutiveZeroRouteRecoveries++;
                    if (consecutiveZeroRouteRecoveries > MAX_ZERO_ROUTE_RECOVERIES) {
                        IllegalStateException routeFailure = new IllegalStateException(
                                "Audio route stayed at zero after repeated VOICE_RECOGNITION recovery."
                        );
                        Logger.e(TAG, "Zero-frame route recovery limit reached.", routeFailure);
                        frameCallback.onRecorderFailure(reason, routeFailure);
                        recording = false;
                        return false;
                    }
                } else {
                    consecutiveZeroRouteRecoveries = 0;
                }

                audioRecord = createAudioRecord();
                audioRecord.startRecording();
                if (audioRecord.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                    throw new IllegalStateException("Recovered AudioRecord failed to enter recording state.");
                }
                Logger.d(TAG, "AudioRecord recovery succeeded on source=" + activeAudioSource);
                return true;
            } catch (RuntimeException runtimeException) {
                recording = false;
                stopAndReleaseAudioRecord();
                Logger.e(TAG, "AudioRecord recovery failed.", runtimeException);
                frameCallback.onRecorderFailure(reason, runtimeException);
                return false;
            }
        }
    }

    private void stopAndReleaseAudioRecord() {
        if (audioRecord != null) {
            try {
                if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop();
                }
            } catch (IllegalStateException ignored) {
            }
        }

        releaseAudioRecord();
    }

    private void releaseAudioRecord() {
        if (audioRecord != null) {
            audioRecord.release();
            audioRecord = null;
        }
        activeAudioSource = -1;
    }

    private AudioRecord buildAudioRecord(int audioSource) {
        AudioRecord createdRecord = new AudioRecord(
                audioSource,
                audioConfig.getSampleRateHz(),
                audioConfig.getChannelConfig(),
                audioConfig.getAudioEncoding(),
                bufferSizeBytes
        );

        if (createdRecord.getState() == AudioRecord.STATE_INITIALIZED) {
            activeAudioSource = audioSource;
            Logger.d(TAG, "Initialized AudioRecord with source=" + audioSource);
            return createdRecord;
        }

        createdRecord.release();
        return null;
    }

    private boolean isAllZeroFrame(short[] frame) {
        for (short sample : frame) {
            if (sample != 0) {
                return false;
            }
        }
        return true;
    }
}
