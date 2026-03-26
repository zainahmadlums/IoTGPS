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
    private static final int CONFIGURED_SPEECH_DURATION_MS = 0;
    private static final int CONFIGURED_SILENCE_DURATION_MS = 300;
    private static final int CONFIGURED_FRAME_SIZE_SAMPLES = 512;
    private static final int CONFIGURED_FRAME_DURATION_MS =
            (CONFIGURED_FRAME_SIZE_SAMPLES * 1000) / AudioConfig.DEFAULT_SAMPLE_RATE_HZ;
    private static final long RESET_AFTER_SILENCE_MS = 1500L;
    private static final long RESET_RETRY_AFTER_SILENCE_MS = 5000L;

    private final Context applicationContext;
    private VadSilero vadDelegate;
    private long lastLogTimestampMillis;
    private long consecutiveSilenceMillis;
    private long silenceSinceLastResetMillis;
    private boolean resetDuringCurrentSilence;

    public SileroSpeechDetector(Context context) {
        this.applicationContext = context.getApplicationContext();
        this.vadDelegate = createVadDelegate();
    }

    @Override
    public VadResult analyze(short[] frame, long timestampMillis) {
        if (frame == null || frame.length != CONFIGURED_FRAME_SIZE_SAMPLES) {
            return new VadResult(timestampMillis, false, null);
        }

        boolean isSpeech = vadDelegate.isSpeech(frame);
        updateSilenceRecovery(isSpeech);
        maybeLog(timestampMillis, frame, isSpeech);
        return new VadResult(timestampMillis, isSpeech, null);
    }

    @Override
    public void close() {
        if (vadDelegate != null) {
            vadDelegate.close();
            vadDelegate = null;
        }
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

    static long resetAfterSilenceMs() {
        return RESET_AFTER_SILENCE_MS;
    }

    static long resetRetryAfterSilenceMs() {
        return RESET_RETRY_AFTER_SILENCE_MS;
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

    private void updateSilenceRecovery(boolean isSpeech) {
        if (isSpeech) {
            consecutiveSilenceMillis = 0L;
            silenceSinceLastResetMillis = 0L;
            resetDuringCurrentSilence = false;
            return;
        }

        consecutiveSilenceMillis += CONFIGURED_FRAME_DURATION_MS;
        silenceSinceLastResetMillis += CONFIGURED_FRAME_DURATION_MS;
        long resetThresholdMillis = resetDuringCurrentSilence
                ? RESET_RETRY_AFTER_SILENCE_MS
                : RESET_AFTER_SILENCE_MS;
        if (silenceSinceLastResetMillis >= resetThresholdMillis) {
            Logger.d(TAG, "Resetting Silero delegate after extended silence.");
            VadSilero previousDelegate = vadDelegate;
            vadDelegate = createVadDelegate();
            previousDelegate.close();
            silenceSinceLastResetMillis = 0L;
            resetDuringCurrentSilence = true;
        }
    }

    private VadSilero createVadDelegate() {
        return new VadSilero(
                applicationContext,
                CONFIGURED_SAMPLE_RATE,
                CONFIGURED_FRAME_SIZE,
                CONFIGURED_MODE,
                CONFIGURED_SPEECH_DURATION_MS,
                CONFIGURED_SILENCE_DURATION_MS
        );
    }
}
