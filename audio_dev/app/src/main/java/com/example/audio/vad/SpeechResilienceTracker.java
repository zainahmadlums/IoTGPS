package com.example.audio.vad;

final class SpeechResilienceTracker {

    private static final long DISTURBANCE_HOLD_MS = 480L;
    private static final float MIN_RUBBING_RMS = 0.05f;
    private static final float MIN_RMS_DROP_AFTER_REDUCTION = 0.015f;
    private static final float MIN_SPECTRAL_FLUX = 0.025f;
    private static final float MIN_ZCR = 0.10f;

    private long lastSpeechTimestampMillis = Long.MIN_VALUE;

    boolean refine(
            long timestampMillis,
            boolean rawSpeech,
            boolean conditionedSpeech,
            float rawRms,
            float conditionedRms,
            float spectralFlux,
            float zcr
    ) {
        boolean directSpeech = rawSpeech || conditionedSpeech;
        if (directSpeech) {
            lastSpeechTimestampMillis = timestampMillis;
            return true;
        }

        if (lastSpeechTimestampMillis == Long.MIN_VALUE) {
            return false;
        }

        if (timestampMillis - lastSpeechTimestampMillis > DISTURBANCE_HOLD_MS) {
            return false;
        }

        boolean rubbingLikely = rawRms >= MIN_RUBBING_RMS
                && (rawRms - conditionedRms) >= MIN_RMS_DROP_AFTER_REDUCTION
                && (spectralFlux >= MIN_SPECTRAL_FLUX || zcr >= MIN_ZCR);
        return rubbingLikely;
    }

    void reset() {
        lastSpeechTimestampMillis = Long.MIN_VALUE;
    }
}
