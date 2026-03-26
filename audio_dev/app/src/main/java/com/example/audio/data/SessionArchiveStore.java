package com.example.audio.data;

import android.content.Context;

import com.example.audio.util.Logger;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.List;

public final class SessionArchiveStore {

    private static final String TAG = "SessionArchiveStore";
    private static final String FILE_NAME = "session_archives.json";
    private static final SessionArchiveStore INSTANCE = new SessionArchiveStore();

    private SessionArchiveStore() {
    }

    public static SessionArchiveStore getInstance() {
        return INSTANCE;
    }

    public synchronized List<SessionArchiveEntry> getEntries(Context context) {
        List<SessionArchiveEntry> entries = new ArrayList<>();
        JSONArray jsonArray = readArchiveArray(context.getApplicationContext());
        for (int index = 0; index < jsonArray.length(); index++) {
            JSONObject jsonObject = jsonArray.optJSONObject(index);
            if (jsonObject == null) {
                continue;
            }
            try {
                entries.add(SessionArchiveEntry.fromJson(jsonObject));
            } catch (JSONException jsonException) {
                Logger.e(TAG, "Failed to parse archived session entry.", jsonException);
            }
        }
        return entries;
    }

    public synchronized void archiveSession(Context context, SessionArchiveEntry entry) {
        List<SessionArchiveEntry> entries = getEntries(context);
        entries.add(0, entry);
        writeEntries(context.getApplicationContext(), entries);
    }

    public synchronized SessionArchiveEntry renameSession(
            Context context,
            String sessionId,
            String updatedTitle
    ) {
        List<SessionArchiveEntry> entries = getEntries(context);
        for (int index = 0; index < entries.size(); index++) {
            SessionArchiveEntry entry = entries.get(index);
            if (!entry.getId().equals(sessionId)) {
                continue;
            }
            String updatedFileName = SessionAudioFileManager.renameAudioFile(
                    context.getApplicationContext(),
                    entry.getGeneratedFilename(),
                    updatedTitle,
                    entry.getStartTimeMillis()
            );
            long updatedFileSizeBytes = SessionAudioFileManager.resolveAudioFile(
                    context.getApplicationContext(),
                    updatedFileName
            ).length();
            SessionArchiveEntry renamedEntry = entry.withTitleAndFilename(
                    updatedTitle,
                    updatedFileName,
                    updatedFileSizeBytes
            );
            entries.set(index, renamedEntry);
            writeEntries(context.getApplicationContext(), entries);
            return renamedEntry;
        }
        return null;
    }

    public synchronized boolean deleteSession(Context context, String sessionId) {
        List<SessionArchiveEntry> entries = getEntries(context);
        for (int index = 0; index < entries.size(); index++) {
            if (!entries.get(index).getId().equals(sessionId)) {
                continue;
            }
            SessionAudioFileManager.deleteAudioFile(
                    context.getApplicationContext(),
                    entries.get(index).getGeneratedFilename()
            );
            entries.remove(index);
            writeEntries(context.getApplicationContext(), entries);
            return true;
        }
        return false;
    }

    private JSONArray readArchiveArray(Context context) {
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(context.openFileInput(FILE_NAME))
        )) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
        } catch (FileNotFoundException ignored) {
            return new JSONArray();
        } catch (IOException ioException) {
            Logger.e(TAG, "Failed to read archived sessions.", ioException);
            return new JSONArray();
        }

        if (builder.length() == 0) {
            return new JSONArray();
        }

        try {
            return new JSONArray(builder.toString());
        } catch (JSONException jsonException) {
            Logger.e(TAG, "Failed to parse archived sessions JSON.", jsonException);
            return new JSONArray();
        }
    }

    private void writeEntries(Context context, List<SessionArchiveEntry> entries) {
        JSONArray jsonArray = new JSONArray();
        for (SessionArchiveEntry entry : entries) {
            try {
                jsonArray.put(entry.toJson());
            } catch (JSONException jsonException) {
                Logger.e(TAG, "Failed to encode archived session entry.", jsonException);
            }
        }

        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(context.openFileOutput(FILE_NAME, Context.MODE_PRIVATE))
        )) {
            writer.write(jsonArray.toString());
        } catch (IOException ioException) {
            Logger.e(TAG, "Failed to write archived sessions.", ioException);
        }
    }
}
