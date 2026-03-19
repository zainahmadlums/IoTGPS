package com.example.audio.disturbance;

import com.example.audio.vad.VadResult;

public interface DisturbanceDetector {

    DisturbanceResult analyze(short[] frame, long timestampMillis, VadResult vadResult);
}
