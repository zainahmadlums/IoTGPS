package com.example.audio.features;

public class ZcrCalculator implements FeatureExtractor {

    @Override
    public float extract(short[] frame) {
        if (frame == null || frame.length < 2) {
            return 0.0f;
        }

        int zeroCrossings = 0;
        for (int index = 1; index < frame.length; index++) {
            short previous = frame[index - 1];
            short current = frame[index];
            if ((previous >= 0 && current < 0) || (previous < 0 && current >= 0)) {
                zeroCrossings++;
            }
        }

        return (float) zeroCrossings / (frame.length - 1);
    }
}
