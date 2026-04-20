package com.example.audio.vad;

final class PocketNoiseReducer {

    private static final float HIGH_PASS_ALPHA = 0.985f;
    private static final float TARGET_RMS = 0.075f;
    private static final float MIN_RMS_FOR_GAIN = 0.012f;
    private static final float MAX_GAIN = 6.0f;
    private static final float GAIN_SMOOTHING_ALPHA = 0.25f;

    private float previousInput;
    private float previousOutput;
    private float smoothedGain = 1.0f;

    short[] condition(short[] frame) {
        if (frame == null) {
            return new short[0];
        }

        short[] conditionedFrame = new short[frame.length];
        if (frame.length == 0) {
            return conditionedFrame;
        }

        float[] highPassed = new float[frame.length];
        double energy = 0.0d;
        for (int index = 0; index < frame.length; index++) {
            float input = frame[index];
            float output = input - previousInput + (HIGH_PASS_ALPHA * previousOutput);
            previousInput = input;
            previousOutput = output;
            highPassed[index] = output;
            energy += output * output;
        }

        float normalizedRms = (float) (Math.sqrt(energy / frame.length) / Short.MAX_VALUE);
        float desiredGain = TARGET_RMS / Math.max(normalizedRms, MIN_RMS_FOR_GAIN);
        desiredGain = clamp(desiredGain, 1.0f, MAX_GAIN);
        smoothedGain += GAIN_SMOOTHING_ALPHA * (desiredGain - smoothedGain);

        for (int index = 0; index < highPassed.length; index++) {
            conditionedFrame[index] = saturate(Math.round(highPassed[index] * smoothedGain));
        }

        return conditionedFrame;
    }

    void reset() {
        previousInput = 0.0f;
        previousOutput = 0.0f;
        smoothedGain = 1.0f;
    }

    private float clamp(float value, float minValue, float maxValue) {
        return Math.max(minValue, Math.min(maxValue, value));
    }

    private short saturate(int sample) {
        if (sample > Short.MAX_VALUE) {
            return Short.MAX_VALUE;
        }
        if (sample < Short.MIN_VALUE) {
            return Short.MIN_VALUE;
        }
        return (short) sample;
    }
}
