package com.example.audio.data;

import android.content.Context;

import com.example.audio.util.Logger;

import java.io.File;
import java.util.Locale;

public final class SessionAudioFileManager {

    private static final String TAG = "SessionAudioFileManager";
    private static final String AUDIO_DIRECTORY = "archived_audio";
    private static final String VARIANT_RAW = "raw";
    private static final String VARIANT_FILTERED = "filtered";
    private static final String VARIANT_INSTRUCTOR = "instructor_setup";

    private SessionAudioFileManager() {
    }

    public static File getAudioDirectory(Context context) {
        File directory = new File(context.getFilesDir(), AUDIO_DIRECTORY);
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("Unable to create archived audio directory.");
        }
        return directory;
    }

    public static File createRawOutputFile(Context context, long startTimeMillis) {
        return new File(getAudioDirectory(context), buildDefaultFileName(startTimeMillis, VARIANT_RAW));
    }

    public static File createConditionedOutputFile(Context context, long startTimeMillis) {
        return new File(getAudioDirectory(context), buildDefaultFileName(startTimeMillis, VARIANT_FILTERED));
    }

    public static File createInstructorEnrollmentOutputFile(Context context, long startTimeMillis) {
        return new File(getAudioDirectory(context), buildInstructorFileName(startTimeMillis));
    }

    public static File resolveAudioFile(Context context, String generatedFilename) {
        return new File(getAudioDirectory(context), generatedFilename);
    }

    public static String buildRenamedFileName(String title, long startTimeMillis, String variant) {
        return String.format(
                Locale.US,
                "%2$s_%3$s_%1$tY%1$tm%1$td_%1$tH%1$tM%1$tS.wav",
                startTimeMillis,
                sanitizeForFileName(title),
                variant
        );
    }

    public static boolean deleteAudioFile(Context context, String generatedFilename) {
        if (generatedFilename == null || generatedFilename.trim().isEmpty()) {
            return true;
        }
        File audioFile = resolveAudioFile(context, generatedFilename);
        return !audioFile.exists() || audioFile.delete();
    }

    public static boolean deleteAllAudioFiles(Context context) {
        File directory = getAudioDirectory(context);
        File[] files = directory.listFiles();
        if (files == null) {
            return true;
        }

        boolean allDeleted = true;
        for (File file : files) {
            if (file.isFile()
                    && !file.getName().contains("_" + VARIANT_INSTRUCTOR + "_")
                    && !file.delete()) {
                allDeleted = false;
                Logger.e(TAG, "Failed to delete archived audio file: " + file.getName());
            }
        }
        return allDeleted;
    }

    public static String renameAudioFile(
            Context context,
            String currentGeneratedFilename,
            String updatedTitle,
            long startTimeMillis
    ) {
        if (currentGeneratedFilename == null || currentGeneratedFilename.trim().isEmpty()) {
            return null;
        }
        File currentFile = resolveAudioFile(context, currentGeneratedFilename);
        if (!currentFile.exists()) {
            return currentGeneratedFilename;
        }

        String updatedFileName = buildRenamedFileName(
                updatedTitle,
                startTimeMillis,
                detectVariant(currentGeneratedFilename)
        );
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

    private static String buildDefaultFileName(long startTimeMillis, String variant) {
        return String.format(
                Locale.US,
                "deployteach_%2$s_%1$tY%1$tm%1$td_%1$tH%1$tM%1$tS.wav",
                startTimeMillis,
                variant
        );
    }

    private static String buildInstructorFileName(long startTimeMillis) {
        return String.format(
                Locale.US,
                "deployteach_%2$s_%1$tY%1$tm%1$td_%1$tH%1$tM%1$tS.wav",
                startTimeMillis,
                VARIANT_INSTRUCTOR
        );
    }

    private static String detectVariant(String fileName) {
        if (fileName != null && fileName.contains("_" + VARIANT_FILTERED + "_")) {
            return VARIANT_FILTERED;
        }
        return VARIANT_RAW;
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
