package com.example.audio.pipeline;

import com.example.audio.reverb.ReverbResult;
import com.example.audio.util.Logger;

public class FrameProcessingDiagnostics {

    private static final String TAG = "FramePipeline";
    private static final long LOG_INTERVAL_MILLIS = 2000L;

    private long windowStartMillis;
    private int frameCount;
    private int speechCount;
    private int disturbanceCount;
    private float reverbScoreSum;

    public void record(FrameAnalysisResult result) {
        if (result == null || result.getVadResult() == null) {
            return;
        }

        long timestampMillis = result.getVadResult().getTimestampMillis();
        if (windowStartMillis == 0L) {
            windowStartMillis = timestampMillis;
        }

        frameCount++;
        if (result.getVadResult().isSpeech()) {
            speechCount++;
        }
        if (result.getDisturbanceResult() != null
                && result.getDisturbanceResult().isDisturbanceDetected()) {
            disturbanceCount++;
        }
        if (result.getReverbResult() != null) {
            reverbScoreSum += result.getReverbResult().getReverbScore();
        }

        if (timestampMillis - windowStartMillis >= LOG_INTERVAL_MILLIS) {
            float speechRatio = frameCount == 0 ? 0.0f : (float) speechCount / frameCount;
            float averageReverbScore = frameCount == 0 ? 0.0f : reverbScoreSum / frameCount;
            ReverbResult.Level reverbLevel = result.getReverbResult() != null
                    ? result.getReverbResult().getLevel()
                    : ReverbResult.Level.LOW;
            Logger.d(
                    TAG,
                    "frames=" + frameCount
                            + ", speechRatio=" + speechRatio
                            + ", disturbances=" + disturbanceCount
                            + ", avgReverbScore=" + averageReverbScore
                            + ", lastReverbLevel=" + reverbLevel
            );
            reset(timestampMillis);
        }
    }

    private void reset(long timestampMillis) {
        windowStartMillis = timestampMillis;
        frameCount = 0;
        speechCount = 0;
        disturbanceCount = 0;
        reverbScoreSum = 0.0f;
    }
}
