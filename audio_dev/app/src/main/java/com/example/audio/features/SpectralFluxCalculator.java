package com.example.audio.features;

public class SpectralFluxCalculator implements FeatureExtractor {

    private short[] previousFrame;

    @Override
    public float extract(short[] frame) {
        if (frame == null || frame.length == 0) {
            return 0.0f;
        }

        if (previousFrame == null || previousFrame.length != frame.length) {
            previousFrame = frame.clone();
            return 0.0f;
        }

        float flux = 0.0f;
        for (int index = 0; index < frame.length; index++) {
            flux += Math.abs(frame[index] - previousFrame[index]) / (float) Short.MAX_VALUE;
        }

        previousFrame = frame.clone();
        return flux / frame.length;
    }
}
