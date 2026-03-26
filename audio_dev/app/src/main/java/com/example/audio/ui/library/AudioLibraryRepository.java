package com.example.audio.ui.library;

import android.content.Context;

import com.example.audio.data.SessionArchiveEntry;
import com.example.audio.data.SessionArchiveStore;
import com.example.audio.data.SessionAudioFileManager;

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

    public File resolveAudioFile(Context context, AudioSessionItem item) {
        return SessionAudioFileManager.resolveAudioFile(
                context.getApplicationContext(),
                item.getAudioFileName()
        );
    }

    private AudioSessionItem toAudioSessionItem(Context context, SessionArchiveEntry entry) {
        File audioFile = SessionAudioFileManager.resolveAudioFile(
                context.getApplicationContext(),
                entry.getGeneratedFilename()
        );
        return new AudioSessionItem(
                entry.getId(),
                entry.getTitle(),
                entry.getGeneratedFilename(),
                entry.getStartTimeMillis(),
                entry.getEndTimeMillis(),
                entry.getDurationMillis(),
                audioFile.exists() ? audioFile.length() : entry.getFileSizeBytes(),
                entry.getSpeechRatio(),
                entry.getDisturbanceCount(),
                entry.getReverbLevel(),
                entry.getGeneratedFilename(),
                audioFile.exists() && audioFile.length() > 44L,
                false
        );
    }
}
