package com.example.audio.vad;

import android.content.Context;

import com.example.audio.audio.AudioConfig;
import com.example.audio.util.Logger;
import com.konovalov.vad.silero.VadSilero;
import com.konovalov.vad.silero.config.FrameSize;
import com.konovalov.vad.silero.config.Mode;
import com.konovalov.vad.silero.config.SampleRate;

public class SileroSpeechDetector implements SpeechDetector {

    private static final String TAG = "SileroSpeechDetector";
    private static final SampleRate CONFIGURED_SAMPLE_RATE = SampleRate.SAMPLE_RATE_16K;
    private static final FrameSize CONFIGURED_FRAME_SIZE = FrameSize.FRAME_SIZE_512;
    private static final Mode CONFIGURED_MODE = Mode.NORMAL;
    private static final int CONFIGURED_SPEECH_DURATION_MS = 50;
    private static final int CONFIGURED_SILENCE_DURATION_MS = 300;
    private static final int CONFIGURED_FRAME_SIZE_SAMPLES = 512;

    private final VadSilero vadDelegate;
    private long lastLogTimestampMillis;

    public SileroSpeechDetector(Context context) {
        this.vadDelegate = new VadSilero(
                context.getApplicationContext(),
                CONFIGURED_SAMPLE_RATE,
                CONFIGURED_FRAME_SIZE,
                CONFIGURED_MODE,
                CONFIGURED_SPEECH_DURATION_MS,
                CONFIGURED_SILENCE_DURATION_MS
        );
    }

    @Override
    public VadResult analyze(short[] frame, long timestampMillis) {
        if (frame == null || frame.length != CONFIGURED_FRAME_SIZE_SAMPLES) {
            return new VadResult(timestampMillis, false, null);
        }

        boolean isSpeech = vadDelegate.isSpeech(frame);
        maybeLog(timestampMillis, frame, isSpeech);
        return new VadResult(timestampMillis, isSpeech, null);
    }

    @Override
    public void close() {
        vadDelegate.close();
    }

    public static boolean supports(AudioConfig audioConfig) {
        return audioConfig.getSampleRateHz() == AudioConfig.DEFAULT_SAMPLE_RATE_HZ
                && audioConfig.getChannelCount() == 1
                && audioConfig.getAudioEncoding() == AudioConfig.DEFAULT_ENCODING
                && audioConfig.getFrameSizeSamples() == CONFIGURED_FRAME_SIZE_SAMPLES
                && audioConfig.getFrameSizeBytes() == CONFIGURED_FRAME_SIZE_SAMPLES * 2;
    }

    static SampleRate configuredSampleRate() {
        return CONFIGURED_SAMPLE_RATE;
    }

    static FrameSize configuredFrameSize() {
        return CONFIGURED_FRAME_SIZE;
    }

    static Mode configuredMode() {
        return CONFIGURED_MODE;
    }

    static int configuredSpeechDurationMs() {
        return CONFIGURED_SPEECH_DURATION_MS;
    }

    static int configuredSilenceDurationMs() {
        return CONFIGURED_SILENCE_DURATION_MS;
    }

    private void maybeLog(long timestampMillis, short[] frame, boolean isSpeech) {
        if (timestampMillis - lastLogTimestampMillis < 1000L) {
            return;
        }

        lastLogTimestampMillis = timestampMillis;
        Logger.d(TAG, "rms=" + computeRms(frame) + ", isSpeech=" + isSpeech);
    }

    private int computeRms(short[] frame) {
        long energy = 0L;
        for (short sample : frame) {
            energy += (long) sample * sample;
        }
        return (int) Math.sqrt((double) energy / frame.length);
    }
}
