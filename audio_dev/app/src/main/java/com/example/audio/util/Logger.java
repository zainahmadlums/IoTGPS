package com.example.audio.util;

public final class Logger {

    private Logger() {
    }

    public static void d(String tag, String message) {
        android.util.Log.d(tag, message);
    }

    public static void e(String tag, String message) {
        android.util.Log.e(tag, message);
    }

    public static void e(String tag, String message, Throwable throwable) {
        android.util.Log.e(tag, message, throwable);
    }
}
