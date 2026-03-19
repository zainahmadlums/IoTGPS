package com.example.audio.pipeline;

import com.example.audio.disturbance.DisturbanceResult;
import com.example.audio.reverb.ReverbResult;
import com.example.audio.vad.VadResult;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class SessionAggregatorTest {

    @Test
    public void summarizesSpeechDisturbanceAndReverb() {
        SessionAggregator aggregator = new SessionAggregator();

        aggregator.add(new FrameAnalysisResult(
                new VadResult(0L, true, null),
                new DisturbanceResult(0L, false, 0.0f),
                new ReverbResult(0L, ReverbResult.Level.LOW, 0.0f)
        ));
        aggregator.add(new FrameAnalysisResult(
                new VadResult(20L, true, null),
                new DisturbanceResult(20L, false, 0.0f),
                new ReverbResult(20L, ReverbResult.Level.MEDIUM, 0.35f)
        ));
        aggregator.add(new FrameAnalysisResult(
                new VadResult(40L, false, null),
                new DisturbanceResult(40L, true, 0.8f),
                new ReverbResult(40L, ReverbResult.Level.MEDIUM, 0.32f)
        ));
        aggregator.add(new FrameAnalysisResult(
                new VadResult(60L, false, null),
                new DisturbanceResult(60L, false, 0.0f),
                new ReverbResult(60L, ReverbResult.Level.HIGH, 0.52f)
        ));

        SessionSummary summary = aggregator.getSummary();

        assertEquals(0.5f, summary.getSpeakingRatio(), 0.0001f);
        assertEquals(1, summary.getDisturbanceCount());
        assertEquals(ReverbResult.Level.MEDIUM, summary.getCoarseReverbLevel());
    }
}
