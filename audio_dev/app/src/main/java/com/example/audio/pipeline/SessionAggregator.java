package com.example.audio.pipeline;

import com.example.audio.reverb.ReverbResult;

public class SessionAggregator {

    private boolean sessionRunning;
    private int totalFrames;
    private int speechFrames;
    private int disturbanceCount;
    private int lowReverbFrames;
    private int mediumReverbFrames;
    private int highReverbFrames;
    private int reverbEvidenceFrames;
    private float reverbScoreSum;

    public void add(FrameAnalysisResult result) {
        if (result == null) {
            return;
        }

        totalFrames++;
        if (result.getVadResult() != null && result.getVadResult().isSpeech()) {
            speechFrames++;
        }
        if (result.getDisturbanceResult() != null
                && result.getDisturbanceResult().isDisturbanceDetected()) {
            disturbanceCount++;
        }
        if (result.getReverbResult() != null) {
            float score = result.getReverbResult().getReverbScore();
            if (score > 0.0f) {
                reverbEvidenceFrames++;
                reverbScoreSum += score;
                ReverbResult.Level level = result.getReverbResult().getLevel();
                if (level == ReverbResult.Level.HIGH) {
                    highReverbFrames++;
                } else if (level == ReverbResult.Level.MEDIUM) {
                    mediumReverbFrames++;
                } else {
                    lowReverbFrames++;
                }
            }
        }
    }

    public SessionSummary getSummary() {
        float speakingRatio = totalFrames == 0 ? 0.0f : (float) speechFrames / totalFrames;
        return new SessionSummary(
                speakingRatio,
                disturbanceCount,
                dominantReverbLevel(),
                sessionRunning
        );
    }

    public void setSessionRunning(boolean sessionRunning) {
        this.sessionRunning = sessionRunning;
    }

    public void reset() {
        sessionRunning = false;
        totalFrames = 0;
        speechFrames = 0;
        disturbanceCount = 0;
        lowReverbFrames = 0;
        mediumReverbFrames = 0;
        highReverbFrames = 0;
        reverbEvidenceFrames = 0;
        reverbScoreSum = 0.0f;
    }

    private ReverbResult.Level dominantReverbLevel() {
        if (totalFrames == 0 || reverbEvidenceFrames == 0) {
            return ReverbResult.Level.LOW;
        }
        float averageReverbScore = reverbScoreSum / reverbEvidenceFrames;
        if (averageReverbScore >= 0.48f) {
            return ReverbResult.Level.HIGH;
        }
        if (averageReverbScore >= 0.28f) {
            return ReverbResult.Level.MEDIUM;
        }
        if (highReverbFrames >= mediumReverbFrames && highReverbFrames >= lowReverbFrames) {
            return ReverbResult.Level.HIGH;
        }
        if (mediumReverbFrames >= lowReverbFrames) {
            return ReverbResult.Level.MEDIUM;
        }
        return ReverbResult.Level.LOW;
    }
}
