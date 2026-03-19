package com.example.audio.vad;

public interface SpeechDetector extends AutoCloseable {

    VadResult analyze(short[] frame, long timestampMillis);

    @Override
    void close();
}
