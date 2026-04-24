package com.example.audio.vad;

import android.content.Context;

import com.example.audio.audio.AudioConfig;
import com.example.audio.features.SpectralFluxCalculator;
import com.example.audio.features.ZcrCalculator;
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
    private final PocketNoiseReducer pocketNoiseReducer = new PocketNoiseReducer();
    private final SpeechResilienceTracker speechResilienceTracker = new SpeechResilienceTracker();
    private final SpectralFluxCalculator spectralFluxCalculator = new SpectralFluxCalculator();
    private final ZcrCalculator zcrCalculator = new ZcrCalculator();
    private VadSilero rawVadDelegate;
    private VadSilero conditionedVadDelegate;
    private long lastLogTimestampMillis;
    private long consecutiveSilenceMillis;
    private long silenceSinceLastResetMillis;
    private boolean resetDuringCurrentSilence;

    public SileroSpeechDetector(Context context) {
        this.applicationContext = context.getApplicationContext();
        this.rawVadDelegate = createVadDelegate();
        this.conditionedVadDelegate = createVadDelegate();
    }

    @Override
    public VadResult analyze(short[] frame, long timestampMillis) {
        if (frame == null || frame.length != CONFIGURED_FRAME_SIZE_SAMPLES) {
            return new VadResult(timestampMillis, false, null, null, null);
        }

        float spectralFlux = spectralFluxCalculator.extract(frame);
        float zcr = zcrCalculator.extract(frame);
        PocketNoiseReducer.Result reductionResult = pocketNoiseReducer.condition(frame, spectralFlux, zcr);
        short[] conditionedFrame = reductionResult.getConditionedFrame();
        boolean rawSpeech = rawVadDelegate.isSpeech(frame);
        boolean conditionedSpeech = conditionedVadDelegate.isSpeech(conditionedFrame);

        SpeechResilienceTracker.Decision decision = speechResilienceTracker.refine(
                timestampMillis,
                rawSpeech,
                conditionedSpeech,
                reductionResult
        );
        boolean isSpeech = decision.isSpeech();
        updateSilenceRecovery(isSpeech);
        maybeLog(
                timestampMillis,
                frame,
                rawSpeech,
                conditionedSpeech,
                decision,
                reductionResult
        );
        return new VadResult(
                timestampMillis,
                isSpeech,
                decision.getConfidence(),
                conditionedFrame,
                reductionResult.getPlaybackFrame()
        );
    }

    @Override
    public void close() {
        closeDelegate(rawVadDelegate);
        closeDelegate(conditionedVadDelegate);
        rawVadDelegate = null;
        conditionedVadDelegate = null;
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

    private void maybeLog(
            long timestampMillis,
            short[] frame,
            boolean rawSpeech,
            boolean conditionedSpeech,
            SpeechResilienceTracker.Decision decision,
            PocketNoiseReducer.Result reductionResult
    ) {
        if (timestampMillis - lastLogTimestampMillis < 1000L) {
            return;
        }

        lastLogTimestampMillis = timestampMillis;
        Logger.d(
                TAG,
                "rawRms="
                        + reductionResult.getRawRms()
                        + ", conditionedRms="
                        + reductionResult.getConditionedRms()
                        + ", flux="
                        + reductionResult.getSpectralFlux()
                        + ", zcr="
                        + reductionResult.getZcr()
                        + ", lowRatio="
                        + reductionResult.getLowBandRatio()
                        + ", highRatio="
                        + reductionResult.getHighBandRatio()
                        + ", voicing="
                        + reductionResult.getVoicingScore()
                        + ", rubbing="
                        + reductionResult.getRubbingScore()
                        + ", speechScore="
                        + reductionResult.getSpeechScore()
                        + ", rawSpeech="
                        + rawSpeech
                        + ", conditionedSpeech="
                        + conditionedSpeech
                        + ", finalSpeech="
                        + decision.isSpeech()
                        + ", confidence="
                        + decision.getConfidence()
                        + ", rawIntRms="
                        + computeRms(frame)
        );
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
            Logger.d(TAG, "Resetting Silero delegates after extended silence.");
            closeDelegate(rawVadDelegate);
            closeDelegate(conditionedVadDelegate);
            rawVadDelegate = createVadDelegate();
            conditionedVadDelegate = createVadDelegate();
            pocketNoiseReducer.reset();
            speechResilienceTracker.reset();
            silenceSinceLastResetMillis = 0L;
            resetDuringCurrentSilence = true;
        }
    }

    private void closeDelegate(VadSilero delegate) {
        if (delegate != null) {
            delegate.close();
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
