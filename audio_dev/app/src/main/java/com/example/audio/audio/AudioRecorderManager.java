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

    public interface FrameCallback {
        void onFrame(short[] frame, long timestampMillis);
    }

    private final Context context;
    private final AudioConfig audioConfig;
    private AudioRecord audioRecord;
    private Thread recordingThread;
    private volatile boolean recording;
    private int bufferSizeBytes;

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

        AudioRecord createdRecord = buildAudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION);
        if (createdRecord == null) {
            createdRecord = buildAudioRecord(MediaRecorder.AudioSource.MIC);
        }

        if (createdRecord == null) {
            throw new IllegalStateException("AudioRecord failed to initialize.");
        }

        return createdRecord;
    }

    private void readLoop(FrameCallback frameCallback) {
        short[] chunkBuffer = new short[Math.max(audioConfig.getFrameSizeSamples(), 1024)];
        short[] frameBuffer = new short[audioConfig.getFrameSizeSamples()];
        int frameOffset = 0;

        while (recording && !Thread.currentThread().isInterrupted()) {
            int samplesRead = audioRecord.read(chunkBuffer, 0, chunkBuffer.length);
            if (samplesRead <= 0) {
                continue;
            }

            int chunkOffset = 0;
            while (chunkOffset < samplesRead && recording) {
                int copyLength = Math.min(frameBuffer.length - frameOffset, samplesRead - chunkOffset);
                System.arraycopy(chunkBuffer, chunkOffset, frameBuffer, frameOffset, copyLength);
                frameOffset += copyLength;
                chunkOffset += copyLength;

                if (frameOffset == frameBuffer.length) {
                    frameCallback.onFrame(
                            Arrays.copyOf(frameBuffer, frameBuffer.length),
                            System.currentTimeMillis()
                    );
                    frameOffset = 0;
                }
            }
        }
    }

    private void releaseAudioRecord() {
        if (audioRecord != null) {
            audioRecord.release();
            audioRecord = null;
        }
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
            Logger.d(TAG, "Initialized AudioRecord with source=" + audioSource);
            return createdRecord;
        }

        createdRecord.release();
        return null;
    }
}
