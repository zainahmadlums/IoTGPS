package com.example.audio.features;

public interface FeatureExtractor {

    float extract(short[] frame);
}
