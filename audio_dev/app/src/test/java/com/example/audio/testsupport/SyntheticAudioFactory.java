package com.example.audio.testsupport;

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
}
