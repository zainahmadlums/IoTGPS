package com.example.audio.ui.library;

import android.content.Context;

import com.example.audio.data.SessionArchiveEntry;
import com.example.audio.data.SessionArchiveStore;
import com.example.audio.data.SessionAudioFileManager;
import com.example.audio.data.SessionMetadata;
import com.example.audio.data.SessionMetadataFileManager;
import com.example.audio.data.SessionMetadataStore;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public final class AudioLibraryRepository {

    private static final AudioLibraryRepository INSTANCE = new AudioLibraryRepository();

    private AudioLibraryRepository() {
    }

    public static AudioLibraryRepository getInstance() {
        return INSTANCE;
    }

    public List<AudioSessionItem> getSessions(Context context) {
        List<AudioSessionItem> items = new ArrayList<>();
        for (SessionArchiveEntry entry : SessionArchiveStore.getInstance().getEntries(context)) {
            items.add(toAudioSessionItem(context, entry));
        }
        return items;
    }

    public AudioSessionItem findSession(Context context, String sessionId) {
        for (AudioSessionItem item : getSessions(context)) {
            if (item.getId().equals(sessionId)) {
                return item;
            }
        }
        return null;
    }

    public AudioSessionItem renameSession(Context context, String sessionId, String updatedTitle) {
        SessionArchiveEntry renamedEntry = SessionArchiveStore.getInstance().renameSession(
                context,
                sessionId,
                updatedTitle
        );
        return renamedEntry == null ? null : toAudioSessionItem(context, renamedEntry);
    }

    public boolean deleteSession(Context context, String sessionId) {
        return SessionArchiveStore.getInstance().deleteSession(context, sessionId);
    }

    public boolean deleteAllSessions(Context context) {
        return SessionArchiveStore.getInstance().deleteAllSessions(context);
    }

    public File resolveMetadataFile(Context context, AudioSessionItem item) {
        return SessionMetadataFileManager.resolveMetadataFile(
                context.getApplicationContext(),
                item.getGeneratedFilename()
        );
    }

    public SessionMetadata getSessionMetadata(Context context, AudioSessionItem item) {
        return SessionMetadataStore.getInstance().readMetadata(
                context.getApplicationContext(),
                item.getGeneratedFilename()
        );
    }

    private AudioSessionItem toAudioSessionItem(Context context, SessionArchiveEntry entry) {
        Context appContext = context.getApplicationContext();
        File metadataFile = SessionMetadataFileManager.resolveMetadataFile(
                appContext,
                entry.getGeneratedFilename()
        );
        SessionMetadata sessionMetadata = SessionMetadataStore.getInstance().readMetadata(
                appContext,
                entry.getGeneratedFilename()
        );
        String preferredAudioFileName = sessionMetadata != null
                ? firstNonEmpty(
                        sessionMetadata.getConditionedAudioFileName(),
                        sessionMetadata.getRawAudioFileName()
                )
                : null;
        boolean playbackAvailable = sessionMetadata != null
                && hasPlaybackFile(appContext, sessionMetadata.getRawAudioFileName())
                && hasPlaybackFile(appContext, sessionMetadata.getConditionedAudioFileName());
        return new AudioSessionItem(
                entry.getId(),
                entry.getTitle(),
                entry.getGeneratedFilename(),
                entry.getStartTimeMillis(),
                entry.getEndTimeMillis(),
                entry.getDurationMillis(),
                metadataFile.exists() ? metadataFile.length() : entry.getFileSizeBytes(),
                entry.getSpeechRatio(),
                entry.getDisturbanceCount(),
                entry.getReverbLevel(),
                preferredAudioFileName,
                playbackAvailable,
                false
        );
    }

    private boolean hasPlaybackFile(Context context, String fileName) {
        return fileName != null
                && !fileName.trim().isEmpty()
                && SessionAudioFileManager.resolveAudioFile(context, fileName).exists();
    }

    private String firstNonEmpty(String primary, String secondary) {
        if (primary != null && !primary.trim().isEmpty()) {
            return primary;
        }
        if (secondary != null && !secondary.trim().isEmpty()) {
            return secondary;
        }
        return null;
    }
}
