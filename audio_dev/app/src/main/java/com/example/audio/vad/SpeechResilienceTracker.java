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
    private static final float ENTRY_THRESHOLD = 0.64f;
    private static final float HOLD_THRESHOLD = 0.41f;
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
        float rubbingScore = reductionResult.getRubbingScore();
        float speechScore = reductionResult.getSpeechScore();
        float occlusionScore = reductionResult.getOcclusionScore();
        float unreliableScore = reductionResult.getUnreliableScore();
        boolean rejectForVad = reductionResult.shouldRejectForVad();
        boolean directSpeech = conditionedSpeech
                && (!rejectForVad
                || speechScore >= 0.64f
                || reductionResult.getVoicingScore() >= 0.72f);
        if (directSpeech) {
            lastDirectSpeechTimestampMillis = timestampMillis;
        }

        float rawWeight = 0.04f + (0.05f * (1.0f - rubbingScore));
        float conditionedWeight = 0.96f + (0.30f * speechScore) - (0.12f * unreliableScore);
        float score = 0.0f;
        float totalWeight = rawWeight + conditionedWeight + 0.46f;

        if (rawSpeech) {
            score += rawWeight;
        }
        if (conditionedSpeech) {
            score += conditionedWeight;
        }
        score += speechScore * 0.46f;

        if (rawSpeech && !conditionedSpeech) {
            score -= 0.34f + (0.28f * rubbingScore) + (0.12f * unreliableScore);
        } else if (!rawSpeech && conditionedSpeech) {
            score += (0.24f * speechScore) + (0.08f * rubbingScore);
            score += 0.14f * reductionResult.getVoicingScore();
        }
        if (conditionedSpeech && reductionResult.getConditionedRms() > 0.010f) {
            score += 0.06f;
        }
        if (rejectForVad) {
            score -= 0.28f + (0.26f * unreliableScore) + (0.10f * occlusionScore);
            totalWeight += 0.32f;
        }
        if (conditionedSpeech && speechScore >= 0.68f && reductionResult.getVoicingScore() >= 0.62f) {
            score += 0.10f;
        }

        float fusedScore = MathUtils.clamp(score / totalWeight, 0.0f, 1.0f);
        float speechThreshold = speechActive ? HOLD_THRESHOLD : ENTRY_THRESHOLD;
        if (conditionedSpeech && speechScore >= 0.52f) {
            speechThreshold -= 0.05f;
        }
        if (conditionedSpeech && reductionResult.getVoicingScore() >= 0.60f) {
            speechThreshold -= 0.03f;
        }
        if (rejectForVad) {
            speechThreshold += 0.09f + (0.06f * unreliableScore);
        }
        speechThreshold = Math.max(EXIT_THRESHOLD + 0.08f, speechThreshold);
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
                && reductionResult.getConditionedRms() >= 0.012f
                && !rejectForVad;

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
