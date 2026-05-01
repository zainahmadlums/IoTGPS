package com.example.audio.util;

public final class Logger {

    private Logger() {
    }

    public static void d(String tag, String message) {
        try {
            android.util.Log.d(tag, message);
        } catch (RuntimeException ignored) {
            // Android Log is not available in local JVM unit tests.
        }
    }

    public static void e(String tag, String message) {
        try {
            android.util.Log.e(tag, message);
        } catch (RuntimeException ignored) {
            // Android Log is not available in local JVM unit tests.
        }
    }

    public static void e(String tag, String message, Throwable throwable) {
        try {
            android.util.Log.e(tag, message, throwable);
        } catch (RuntimeException ignored) {
            // Android Log is not available in local JVM unit tests.
        }
    }
}
