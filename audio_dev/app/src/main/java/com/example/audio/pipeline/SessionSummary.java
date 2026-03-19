package com.example.audio.pipeline;

import com.example.audio.reverb.ReverbResult;

public class SessionSummary {

    private final float speakingRatio;
    private final int disturbanceCount;
    private final ReverbResult.Level coarseReverbLevel;
    private final boolean sessionRunning;

    public SessionSummary(
            float speakingRatio,
            int disturbanceCount,
            ReverbResult.Level coarseReverbLevel,
            boolean sessionRunning
    ) {
        this.speakingRatio = speakingRatio;
        this.disturbanceCount = disturbanceCount;
        this.coarseReverbLevel = coarseReverbLevel;
        this.sessionRunning = sessionRunning;
    }

    public float getSpeakingRatio() {
        return speakingRatio;
    }

    public int getDisturbanceCount() {
        return disturbanceCount;
    }

    public ReverbResult.Level getCoarseReverbLevel() {
        return coarseReverbLevel;
    }

    public boolean isSessionRunning() {
        return sessionRunning;
    }
}
