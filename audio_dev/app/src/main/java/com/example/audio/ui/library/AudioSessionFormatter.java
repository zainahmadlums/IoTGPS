package com.example.audio.ui.library;

import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

public final class AudioSessionFormatter {

    private static final SimpleDateFormat DATE_FORMAT =
            new SimpleDateFormat("MMM d, yyyy • h:mm a", Locale.US);
    private static final SimpleDateFormat TIME_FORMAT =
            new SimpleDateFormat("h:mm a", Locale.US);
    private static final DecimalFormat FILE_SIZE_FORMAT = new DecimalFormat("0.0");

    private AudioSessionFormatter() {
    }

    public static String formatDateTime(long timestampMillis) {
        return DATE_FORMAT.format(new Date(timestampMillis));
    }

    public static String formatTimeRange(long startMillis, long endMillis) {
        return TIME_FORMAT.format(new Date(startMillis))
                + " – "
                + TIME_FORMAT.format(new Date(endMillis));
    }

    public static String formatDuration(long durationMillis) {
        long totalSeconds = Math.max(0L, durationMillis / 1000L);
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        if (hours > 0L) {
            return hours + "h " + minutes + "m " + seconds + "s";
        }
        if (minutes > 0L) {
            return minutes + "m " + seconds + "s";
        }
        return seconds + "s";
    }

    public static String formatClockDuration(long durationMillis) {
        long totalSeconds = Math.max(0L, durationMillis / 1000L);
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        return String.format(Locale.US, "%02d:%02d", minutes, seconds);
    }

    public static String formatFileSize(long fileSizeBytes) {
        if (fileSizeBytes >= 1024L * 1024L) {
            return FILE_SIZE_FORMAT.format(fileSizeBytes / (1024f * 1024f)) + " MB";
        }
        return FILE_SIZE_FORMAT.format(fileSizeBytes / 1024f) + " KB";
    }

    public static boolean isToday(long timestampMillis) {
        Calendar now = Calendar.getInstance();
        Calendar value = Calendar.getInstance();
        value.setTimeInMillis(timestampMillis);
        return now.get(Calendar.YEAR) == value.get(Calendar.YEAR)
                && now.get(Calendar.DAY_OF_YEAR) == value.get(Calendar.DAY_OF_YEAR);
    }
}
