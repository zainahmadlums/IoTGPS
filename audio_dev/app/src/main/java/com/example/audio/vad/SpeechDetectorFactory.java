package com.example.audio.vad;

import android.content.Context;

import com.example.audio.audio.AudioConfig;

public final class SpeechDetectorFactory {

    private SpeechDetectorFactory() {
    }

    public static String activeDetectorName() {
        return "Silero";
    }

    public static AudioConfig activeAudioConfig() {
        return AudioConfig.sileroConfig();
    }

    public static SpeechDetector create(Context context) {
        AudioConfig audioConfig = activeAudioConfig();
        if (!SileroSpeechDetector.supports(audioConfig)) {
            throw new IllegalStateException(
                    "Silero VAD configuration must use 16 kHz mono PCM16 512-sample frames."
            );
        }
        return new SileroSpeechDetector(context);
    }
}
