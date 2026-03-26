package com.example.audio.data;

import android.content.Context;

import com.example.audio.util.Logger;

import java.io.File;
import java.util.Locale;

public final class SessionAudioFileManager {

    private static final String TAG = "SessionAudioFileManager";
    private static final String AUDIO_DIRECTORY = "archived_audio";

    private SessionAudioFileManager() {
    }

    public static File getAudioDirectory(Context context) {
        File directory = new File(context.getFilesDir(), AUDIO_DIRECTORY);
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("Unable to create archived audio directory.");
        }
        return directory;
    }

    public static File createOutputFile(Context context, long startTimeMillis) {
        return new File(getAudioDirectory(context), buildDefaultFileName(startTimeMillis));
    }

    public static File resolveAudioFile(Context context, String generatedFilename) {
        return new File(getAudioDirectory(context), generatedFilename);
    }

    public static String buildRenamedFileName(String title, long startTimeMillis) {
        return String.format(
                Locale.US,
                "%s_%1$tY%1$tm%1$td_%1$tH%1$tM%1$tS.wav",
                startTimeMillis,
                sanitizeForFileName(title)
        );
    }

    public static boolean deleteAudioFile(Context context, String generatedFilename) {
        File audioFile = resolveAudioFile(context, generatedFilename);
        return !audioFile.exists() || audioFile.delete();
    }

    public static String renameAudioFile(
            Context context,
            String currentGeneratedFilename,
            String updatedTitle,
            long startTimeMillis
    ) {
        File currentFile = resolveAudioFile(context, currentGeneratedFilename);
        if (!currentFile.exists()) {
            return currentGeneratedFilename;
        }

        String updatedFileName = buildRenamedFileName(updatedTitle, startTimeMillis);
        File updatedFile = resolveAudioFile(context, updatedFileName);
        if (updatedFile.exists()) {
            updatedFileName = System.currentTimeMillis() + "_" + updatedFileName;
            updatedFile = resolveAudioFile(context, updatedFileName);
        }

        if (!currentFile.renameTo(updatedFile)) {
            Logger.e(TAG, "Failed to rename archived audio file: " + currentGeneratedFilename);
            return currentGeneratedFilename;
        }
        return updatedFileName;
    }

    private static String buildDefaultFileName(long startTimeMillis) {
        return String.format(
                Locale.US,
                "deployteach_%1$tY%1$tm%1$td_%1$tH%1$tM%1$tS.wav",
                startTimeMillis
        );
    }

    private static String sanitizeForFileName(String value) {
        String sanitized = value == null ? "" : value.trim().toLowerCase(Locale.US);
        sanitized = sanitized.replaceAll("[^a-z0-9]+", "_");
        sanitized = sanitized.replaceAll("^_+|_+$", "");
        if (sanitized.isEmpty()) {
            return "deployteach_session";
        }
        return sanitized;
    }
}
