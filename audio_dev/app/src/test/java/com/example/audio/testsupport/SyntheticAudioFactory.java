package com.example.audio.testsupport;

import com.example.audio.audio.AudioConfig;

public final class SyntheticAudioFactory {

    private SyntheticAudioFactory() {
    }

    public static short[] constantFrame(int size, int amplitude) {
        short[] frame = new short[size];
        short value = (short) amplitude;
        for (int index = 0; index < size; index++) {
            frame[index] = value;
        }
        return frame;
    }

    public static short[] alternatingFrame(int size, int amplitude) {
        short[] frame = new short[size];
        short value = (short) amplitude;
        for (int index = 0; index < size; index++) {
            frame[index] = index % 2 == 0 ? value : (short) -value;
        }
        return frame;
    }

    public static short[] sineFrame(int size, int amplitude, float frequencyHz) {
        short[] frame = new short[size];
        double sampleRateHz = AudioConfig.sileroConfig().getSampleRateHz();
        for (int index = 0; index < size; index++) {
            double angle = (2.0d * Math.PI * frequencyHz * index) / sampleRateHz;
            frame[index] = (short) Math.round(Math.sin(angle) * amplitude);
        }
        return frame;
    }

    public static short[] mixedFrame(short[] primary, short[] secondary) {
        int size = Math.min(primary.length, secondary.length);
        short[] frame = new short[size];
        for (int index = 0; index < size; index++) {
            int mixed = primary[index] + secondary[index];
            if (mixed > Short.MAX_VALUE) {
                mixed = Short.MAX_VALUE;
            } else if (mixed < Short.MIN_VALUE) {
                mixed = Short.MIN_VALUE;
            }
            frame[index] = (short) mixed;
        }
        return frame;
    }

    public static int currentFrameSizeSamples() {
        return AudioConfig.sileroConfig().getFrameSizeSamples();
    }

    public static long currentFrameDurationMillis() {
        return AudioConfig.sileroConfig().getFrameDurationMs();
    }

    public static short[] currentConstantFrame(int amplitude) {
        return constantFrame(currentFrameSizeSamples(), amplitude);
    }

    public static short[] currentAlternatingFrame(int amplitude) {
        return alternatingFrame(currentFrameSizeSamples(), amplitude);
    }

    public static short[] currentSineFrame(int amplitude, float frequencyHz) {
        return sineFrame(currentFrameSizeSamples(), amplitude, frequencyHz);
    }
}
