package com.example.audio.audio;

import android.media.AudioFormat;

public class AudioConfig {

    public static final int DEFAULT_SAMPLE_RATE_HZ = 16000;
    public static final int DEFAULT_FRAME_DURATION_MS = 20;
    public static final int DEFAULT_CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    public static final int DEFAULT_ENCODING = AudioFormat.ENCODING_PCM_16BIT;

    private final int sampleRateHz;
    private final int channelCount;
    private final int channelConfig;
    private final int audioEncoding;
    private final int frameDurationMs;
    private final int frameSizeSamples;

    public AudioConfig(
            int sampleRateHz,
            int channelCount,
            int channelConfig,
            int audioEncoding,
            int frameDurationMs,
            int frameSizeSamples
    ) {
        this.sampleRateHz = sampleRateHz;
        this.channelCount = channelCount;
        this.channelConfig = channelConfig;
        this.audioEncoding = audioEncoding;
        this.frameDurationMs = frameDurationMs;
        this.frameSizeSamples = frameSizeSamples;
    }

    public static AudioConfig defaultConfig() {
        int frameSizeSamples = (DEFAULT_SAMPLE_RATE_HZ * DEFAULT_FRAME_DURATION_MS) / 1000;
        return new AudioConfig(
                DEFAULT_SAMPLE_RATE_HZ,
                1,
                DEFAULT_CHANNEL_CONFIG,
                DEFAULT_ENCODING,
                DEFAULT_FRAME_DURATION_MS,
                frameSizeSamples
        );
    }

    public int getSampleRateHz() {
        return sampleRateHz;
    }

    public int getChannelCount() {
        return channelCount;
    }

    public int getChannelConfig() {
        return channelConfig;
    }

    public int getAudioEncoding() {
        return audioEncoding;
    }

    public int getFrameDurationMs() {
        return frameDurationMs;
    }

    public int getFrameSizeSamples() {
        return frameSizeSamples;
    }

    public int getBytesPerSample() {
        return 2;
    }

    public int getFrameSizeBytes() {
        return frameSizeSamples * channelCount * getBytesPerSample();
    }
}
