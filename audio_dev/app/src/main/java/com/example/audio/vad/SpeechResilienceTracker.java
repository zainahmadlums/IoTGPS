package com.example.audio.vad;

import com.example.audio.util.MathUtils;

final class SpeechResilienceTracker {

    static final class Decision {
        private final boolean speech;
        private final float confidence;

        Decision(boolean speech, float confidence) {
            this.speech = speech;
            this.confidence = confidence;
        }

        boolean isSpeech() {
            return speech;
        }

        float getConfidence() {
            return confidence;
        }
    }

    private static final long RUBBING_HOLD_MS = 480L;
    private static final int ENTRY_FRAME_REQUIREMENT = 2;
    private static final int EXIT_FRAME_REQUIREMENT = 3;
    private static final float ENTRY_THRESHOLD = 0.66f;
    private static final float HOLD_THRESHOLD = 0.43f;
    private static final float EXIT_THRESHOLD = 0.28f;
    private static final float RUBBING_HOLD_THRESHOLD = 0.55f;

    private boolean speechActive;
    private int consecutiveSpeechLeanFrames;
    private int consecutiveSilenceLeanFrames;
    private long lastDirectSpeechTimestampMillis = Long.MIN_VALUE;

    Decision refine(
            long timestampMillis,
            boolean rawSpeech,
            boolean conditionedSpeech,
            PocketNoiseReducer.Result reductionResult
    ) {
        boolean directSpeech = rawSpeech || conditionedSpeech;
        if (directSpeech) {
            lastDirectSpeechTimestampMillis = timestampMillis;
        }

        float rubbingScore = reductionResult.getRubbingScore();
        float speechScore = reductionResult.getSpeechScore();
        float rawWeight = 0.52f + (0.23f * (1.0f - rubbingScore));
        float conditionedWeight = 0.48f + (0.30f * rubbingScore);
        float score = 0.0f;
        float totalWeight = rawWeight + conditionedWeight + 0.32f;

        if (rawSpeech) {
            score += rawWeight;
        }
        if (conditionedSpeech) {
            score += conditionedWeight;
        }
        score += speechScore * 0.32f;

        if (rawSpeech && !conditionedSpeech) {
            score -= 0.14f * rubbingScore;
        } else if (!rawSpeech && conditionedSpeech) {
            score += (0.22f * speechScore) + (0.10f * rubbingScore);
        }

        if (reductionResult.getConditionedRms() > reductionResult.getRawRms()) {
            score += 0.05f;
        }

        float fusedScore = MathUtils.clamp(score / totalWeight, 0.0f, 1.0f);
        float speechThreshold = speechActive ? HOLD_THRESHOLD : ENTRY_THRESHOLD;
        boolean speechLean = fusedScore >= speechThreshold;
        boolean silenceLean = fusedScore <= EXIT_THRESHOLD;

        if (speechLean) {
            consecutiveSpeechLeanFrames++;
            consecutiveSilenceLeanFrames = 0;
        } else if (silenceLean) {
            consecutiveSilenceLeanFrames++;
            consecutiveSpeechLeanFrames = 0;
        } else {
            consecutiveSpeechLeanFrames = Math.max(0, consecutiveSpeechLeanFrames - 1);
            consecutiveSilenceLeanFrames = Math.max(0, consecutiveSilenceLeanFrames - 1);
        }

        if (!speechActive && consecutiveSpeechLeanFrames >= ENTRY_FRAME_REQUIREMENT) {
            speechActive = true;
        }

        boolean recentDirectSpeech = lastDirectSpeechTimestampMillis != Long.MIN_VALUE
                && (timestampMillis - lastDirectSpeechTimestampMillis) <= RUBBING_HOLD_MS;
        boolean rubbingHold = recentDirectSpeech
                && rubbingScore >= RUBBING_HOLD_THRESHOLD
                && speechScore >= 0.18f
                && reductionResult.getConditionedRms() >= 0.012f;

        if (speechActive
                && consecutiveSilenceLeanFrames >= EXIT_FRAME_REQUIREMENT
                && !rubbingHold) {
            speechActive = false;
        }

        float confidence = speechActive ? Math.max(fusedScore, speechThreshold) : fusedScore;
        return new Decision(speechActive, confidence);
    }

    void reset() {
        speechActive = false;
        consecutiveSpeechLeanFrames = 0;
        consecutiveSilenceLeanFrames = 0;
        lastDirectSpeechTimestampMillis = Long.MIN_VALUE;
    }
}
