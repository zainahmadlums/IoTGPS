package com.example.audio.data;

import android.content.Context;

import com.example.audio.util.Logger;

import java.io.File;
import java.util.Locale;

public final class SessionMetadataFileManager {

    private static final String TAG = "SessionMetadataFiles";
    private static final String METADATA_DIRECTORY = "session_metadata";

    private SessionMetadataFileManager() {
    }

    public static File getMetadataDirectory(Context context) {
        File directory = new File(context.getFilesDir(), METADATA_DIRECTORY);
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("Unable to create session metadata directory.");
        }
        return directory;
    }

    public static File createOutputFile(Context context, long startTimeMillis) {
        return new File(getMetadataDirectory(context), buildDefaultFileName(startTimeMillis));
    }

    public static File resolveMetadataFile(Context context, String metadataFileName) {
        return new File(getMetadataDirectory(context), metadataFileName);
    }

    public static String renameMetadataFile(
            Context context,
            String currentMetadataFileName,
            String updatedTitle,
            long startTimeMillis
    ) {
        File currentFile = resolveMetadataFile(context, currentMetadataFileName);
        if (!currentFile.exists()) {
            return currentMetadataFileName;
        }

        String updatedFileName = buildRenamedFileName(updatedTitle, startTimeMillis);
        File updatedFile = resolveMetadataFile(context, updatedFileName);
        if (updatedFile.exists()) {
            updatedFileName = System.currentTimeMillis() + "_" + updatedFileName;
            updatedFile = resolveMetadataFile(context, updatedFileName);
        }

        if (!currentFile.renameTo(updatedFile)) {
            Logger.e(TAG, "Failed to rename metadata file: " + currentMetadataFileName);
            return currentMetadataFileName;
        }
        return updatedFileName;
    }

    public static boolean deleteMetadataFile(Context context, String metadataFileName) {
        File metadataFile = resolveMetadataFile(context, metadataFileName);
        return !metadataFile.exists() || metadataFile.delete();
    }

    public static boolean deleteAllMetadataFiles(Context context) {
        File directory = getMetadataDirectory(context);
        File[] files = directory.listFiles();
        if (files == null) {
            return true;
        }

        boolean allDeleted = true;
        for (File file : files) {
            if (file.isFile()
                    && !InstructorVoiceProfileStore.PROFILE_METADATA_FILE_NAME.equals(file.getName())
                    && !file.delete()) {
                allDeleted = false;
                Logger.e(TAG, "Failed to delete session metadata file: " + file.getName());
            }
        }
        return allDeleted;
    }

    private static String buildDefaultFileName(long startTimeMillis) {
        return String.format(
                Locale.US,
                "session_metadata_%1$tY%1$tm%1$td_%1$tH%1$tM%1$tS.json",
                startTimeMillis
        );
    }

    private static String buildRenamedFileName(String title, long startTimeMillis) {
        return String.format(
                Locale.US,
                "%2$s_%1$tY%1$tm%1$td_%1$tH%1$tM%1$tS.json",
                startTimeMillis,
                sanitizeForFileName(title)
        );
    }

    private static String sanitizeForFileName(String value) {
        String sanitized = value == null ? "" : value.trim().toLowerCase(Locale.US);
        sanitized = sanitized.replaceAll("[^a-z0-9]+", "_");
        sanitized = sanitized.replaceAll("^_+|_+$", "");
        if (sanitized.isEmpty()) {
            return "deployteach_metadata";
        }
        return sanitized;
    }
}
