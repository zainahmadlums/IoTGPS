package com.example.audio.audio;

public class CircularAudioBuffer {

    private final short[] buffer;
    private int writeIndex;

    public CircularAudioBuffer(int capacity) {
        this.buffer = new short[Math.max(capacity, 1)];
    }

    public int capacity() {
        return buffer.length;
    }

    public void write(short[] frame) {
        if (frame == null) {
            return;
        }

        for (short sample : frame) {
            buffer[writeIndex] = sample;
            writeIndex = (writeIndex + 1) % buffer.length;
        }
    }
}
