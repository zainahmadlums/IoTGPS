package com.example.audio.data;

import com.example.audio.reverb.ReverbResult;

import org.json.JSONException;
import org.json.JSONObject;

public class SessionArchiveEntry {

    private static final String KEY_ID = "id";
    private static final String KEY_TITLE = "title";
    private static final String KEY_GENERATED_FILENAME = "generatedFilename";
    private static final String KEY_START_TIME_MILLIS = "startTimeMillis";
    private static final String KEY_END_TIME_MILLIS = "endTimeMillis";
    private static final String KEY_DURATION_MILLIS = "durationMillis";
    private static final String KEY_FILE_SIZE_BYTES = "fileSizeBytes";
    private static final String KEY_SPEECH_RATIO = "speechRatio";
    private static final String KEY_DISTURBANCE_COUNT = "disturbanceCount";
    private static final String KEY_REVERB_LEVEL = "reverbLevel";

    private final String id;
    private final String title;
    private final String generatedFilename;
    private final long startTimeMillis;
    private final long endTimeMillis;
    private final long durationMillis;
    private final long fileSizeBytes;
    private final float speechRatio;
    private final int disturbanceCount;
    private final ReverbResult.Level reverbLevel;

    public SessionArchiveEntry(
            String id,
            String title,
            String generatedFilename,
            long startTimeMillis,
            long endTimeMillis,
            long durationMillis,
            long fileSizeBytes,
            float speechRatio,
            int disturbanceCount,
            ReverbResult.Level reverbLevel
    ) {
        this.id = id;
        this.title = title;
        this.generatedFilename = generatedFilename;
        this.startTimeMillis = startTimeMillis;
        this.endTimeMillis = endTimeMillis;
        this.durationMillis = durationMillis;
        this.fileSizeBytes = fileSizeBytes;
        this.speechRatio = speechRatio;
        this.disturbanceCount = disturbanceCount;
        this.reverbLevel = reverbLevel;
    }

    public String getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getGeneratedFilename() {
        return generatedFilename;
    }

    public long getStartTimeMillis() {
        return startTimeMillis;
    }

    public long getEndTimeMillis() {
        return endTimeMillis;
    }

    public long getDurationMillis() {
        return durationMillis;
    }

    public long getFileSizeBytes() {
        return fileSizeBytes;
    }

    public float getSpeechRatio() {
        return speechRatio;
    }

    public int getDisturbanceCount() {
        return disturbanceCount;
    }

    public ReverbResult.Level getReverbLevel() {
        return reverbLevel;
    }

    public SessionArchiveEntry withTitle(String updatedTitle) {
        return new SessionArchiveEntry(
                id,
                updatedTitle,
                generatedFilename,
                startTimeMillis,
                endTimeMillis,
                durationMillis,
                fileSizeBytes,
                speechRatio,
                disturbanceCount,
                reverbLevel
        );
    }

    public SessionArchiveEntry withTitleAndFilename(
            String updatedTitle,
            String updatedGeneratedFilename,
            long updatedFileSizeBytes
    ) {
        return new SessionArchiveEntry(
                id,
                updatedTitle,
                updatedGeneratedFilename,
                startTimeMillis,
                endTimeMillis,
                durationMillis,
                updatedFileSizeBytes,
                speechRatio,
                disturbanceCount,
                reverbLevel
        );
    }

    public JSONObject toJson() throws JSONException {
        JSONObject jsonObject = new JSONObject();
        jsonObject.put(KEY_ID, id);
        jsonObject.put(KEY_TITLE, title);
        jsonObject.put(KEY_GENERATED_FILENAME, generatedFilename);
        jsonObject.put(KEY_START_TIME_MILLIS, startTimeMillis);
        jsonObject.put(KEY_END_TIME_MILLIS, endTimeMillis);
        jsonObject.put(KEY_DURATION_MILLIS, durationMillis);
        jsonObject.put(KEY_FILE_SIZE_BYTES, fileSizeBytes);
        jsonObject.put(KEY_SPEECH_RATIO, speechRatio);
        jsonObject.put(KEY_DISTURBANCE_COUNT, disturbanceCount);
        jsonObject.put(KEY_REVERB_LEVEL, reverbLevel.name());
        return jsonObject;
    }

    public static SessionArchiveEntry fromJson(JSONObject jsonObject) throws JSONException {
        return new SessionArchiveEntry(
                jsonObject.getString(KEY_ID),
                jsonObject.getString(KEY_TITLE),
                jsonObject.getString(KEY_GENERATED_FILENAME),
                jsonObject.getLong(KEY_START_TIME_MILLIS),
                jsonObject.getLong(KEY_END_TIME_MILLIS),
                jsonObject.getLong(KEY_DURATION_MILLIS),
                jsonObject.getLong(KEY_FILE_SIZE_BYTES),
                (float) jsonObject.getDouble(KEY_SPEECH_RATIO),
                jsonObject.getInt(KEY_DISTURBANCE_COUNT),
                ReverbResult.Level.valueOf(jsonObject.getString(KEY_REVERB_LEVEL))
        );
    }
}
