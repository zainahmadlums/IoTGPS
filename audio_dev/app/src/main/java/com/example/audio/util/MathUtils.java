package com.example.audio.util;

public final class MathUtils {

    private MathUtils() {
    }

    public static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
