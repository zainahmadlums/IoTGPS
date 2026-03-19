package com.example.audio.features;

public class RmsCalculator implements FeatureExtractor {

    @Override
    public float extract(short[] frame) {
        if (frame == null || frame.length == 0) {
            return 0.0f;
        }

        double energy = 0.0d;
        for (short sample : frame) {
            energy += (double) sample * sample;
        }

        double rms = Math.sqrt(energy / frame.length);
        return (float) (rms / Short.MAX_VALUE);
    }
}
