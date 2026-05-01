package com.example.audio.data;

import org.json.JSONException;
import org.json.JSONObject;

public final class SessionDiarizationChunk {

    private static final String KEY_AUDIO_FILE_NAME = "audioFileName";
    private static final String KEY_METADATA_FILE_NAME = "metadataFileName";
    private static final String KEY_START_OFFSET_MILLIS = "startOffsetMillis";
    private static final String KEY_END_OFFSET_MILLIS = "endOffsetMillis";
    private static final String KEY_DURATION_MILLIS = "durationMillis";

    private final String audioFileName;
    private final String metadataFileName;
    private final long startOffsetMillis;
    private final long endOffsetMillis;
    private final long durationMillis;

    public SessionDiarizationChunk(String audioFileName, long startOffsetMillis, long endOffsetMillis) {
        this(audioFileName, null, startOffsetMillis, endOffsetMillis);
    }

    public SessionDiarizationChunk(
            String audioFileName,
            String metadataFileName,
            long startOffsetMillis,
            long endOffsetMillis
    ) {
        this.audioFileName = audioFileName;
        this.metadataFileName = metadataFileName;
        this.startOffsetMillis = startOffsetMillis;
        this.endOffsetMillis = endOffsetMillis;
        this.durationMillis = Math.max(0L, endOffsetMillis - startOffsetMillis);
    }

    public String getAudioFileName() {
        return audioFileName;
    }

    public String getMetadataFileName() {
        return metadataFileName;
    }

    public long getStartOffsetMillis() {
        return startOffsetMillis;
    }

    public long getEndOffsetMillis() {
        return endOffsetMillis;
    }

    public long getDurationMillis() {
        return durationMillis;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject jsonObject = new JSONObject();
        jsonObject.put(KEY_AUDIO_FILE_NAME, audioFileName);
        jsonObject.put(KEY_METADATA_FILE_NAME, metadataFileName);
        jsonObject.put(KEY_START_OFFSET_MILLIS, startOffsetMillis);
        jsonObject.put(KEY_END_OFFSET_MILLIS, endOffsetMillis);
        jsonObject.put(KEY_DURATION_MILLIS, durationMillis);
        return jsonObject;
    }

    public static SessionDiarizationChunk fromJson(JSONObject jsonObject) {
        long startOffsetMillis = jsonObject.optLong(KEY_START_OFFSET_MILLIS, 0L);
        long endOffsetMillis = jsonObject.optLong(KEY_END_OFFSET_MILLIS, startOffsetMillis);
        return new SessionDiarizationChunk(
                jsonObject.optString(KEY_AUDIO_FILE_NAME, null),
                jsonObject.optString(KEY_METADATA_FILE_NAME, null),
                startOffsetMillis,
                endOffsetMillis
        );
    }
}
