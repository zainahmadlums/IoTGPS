package com.example.audio.audio;

import com.example.audio.pipeline.FrameAnalysisResult;

public interface FrameProcessor {

    FrameAnalysisResult process(short[] frame);
}
