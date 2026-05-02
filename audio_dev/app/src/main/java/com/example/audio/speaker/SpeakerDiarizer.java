package com.example.audio.speaker;

import com.example.audio.vad.VadResult;

import com.example.audio.data.SpeakerRole;
import com.example.audio.vad.VadResult;

public interface SpeakerDiarizer {
    SpeakerRole classify(short[] frame, VadResult vadResult);
    default void close() {}

    SpeakerRole[] processAll(short[] samples);
}
