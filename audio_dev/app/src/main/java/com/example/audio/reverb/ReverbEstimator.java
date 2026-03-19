package com.example.audio.reverb;

import com.example.audio.vad.VadResult;

public interface ReverbEstimator {

    ReverbResult estimate(short[] frame, long timestampMillis, VadResult vadResult);
}
