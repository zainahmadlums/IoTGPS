package com.example.audio.pipeline;

import com.example.audio.disturbance.DisturbanceResult;
import com.example.audio.reverb.ReverbResult;
import com.example.audio.vad.VadResult;

public class FrameAnalysisResult {

    private final VadResult vadResult;
    private final DisturbanceResult disturbanceResult;
    private final ReverbResult reverbResult;

    public FrameAnalysisResult(
            VadResult vadResult,
            DisturbanceResult disturbanceResult,
            ReverbResult reverbResult
    ) {
        this.vadResult = vadResult;
        this.disturbanceResult = disturbanceResult;
        this.reverbResult = reverbResult;
    }

    public VadResult getVadResult() {
        return vadResult;
    }

    public DisturbanceResult getDisturbanceResult() {
        return disturbanceResult;
    }

    public ReverbResult getReverbResult() {
        return reverbResult;
    }
}
