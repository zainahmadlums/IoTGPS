package com.example.audio.data;

import org.json.JSONException;
import org.json.JSONObject;

public final class SessionSpeechInterval {

    private static final String KEY_START_OFFSET_MILLIS = "startOffsetMillis";
    private static final String KEY_END_OFFSET_MILLIS = "endOffsetMillis";
    private static final String KEY_DURATION_MILLIS = "durationMillis";

    private final long startOffsetMillis;
    private final long endOffsetMillis;
    private final long durationMillis;

    public SessionSpeechInterval(
            long startOffsetMillis,
            long endOffsetMillis,
            long durationMillis
    ) {
        this.startOffsetMillis = startOffsetMillis;
        this.endOffsetMillis = endOffsetMillis;
        this.durationMillis = durationMillis;
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
        jsonObject.put(KEY_START_OFFSET_MILLIS, startOffsetMillis);
        jsonObject.put(KEY_END_OFFSET_MILLIS, endOffsetMillis);
        jsonObject.put(KEY_DURATION_MILLIS, durationMillis);
        return jsonObject;
    }

    public static SessionSpeechInterval fromJson(JSONObject jsonObject) throws JSONException {
        return new SessionSpeechInterval(
                jsonObject.getLong(KEY_START_OFFSET_MILLIS),
                jsonObject.getLong(KEY_END_OFFSET_MILLIS),
                jsonObject.getLong(KEY_DURATION_MILLIS)
        );
    }
}
