package com.example.audio.data;

import org.json.JSONException;
import org.json.JSONObject;

public final class SessionRoleInterval {

    private static final String KEY_ROLE = "role";
    private static final String KEY_START_OFFSET_MILLIS = "startOffsetMillis";
    private static final String KEY_END_OFFSET_MILLIS = "endOffsetMillis";
    private static final String KEY_DURATION_MILLIS = "durationMillis";

    private final SpeakerRole role;
    private final long startOffsetMillis;
    private final long endOffsetMillis;
    private final long durationMillis;

    public SessionRoleInterval(
            SpeakerRole role,
            long startOffsetMillis,
            long endOffsetMillis,
            long durationMillis
    ) {
        this.role = role;
        this.startOffsetMillis = startOffsetMillis;
        this.endOffsetMillis = endOffsetMillis;
        this.durationMillis = durationMillis;
    }

    public SpeakerRole getRole() {
        return role;
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
        jsonObject.put(KEY_ROLE, role.name());
        jsonObject.put(KEY_START_OFFSET_MILLIS, startOffsetMillis);
        jsonObject.put(KEY_END_OFFSET_MILLIS, endOffsetMillis);
        jsonObject.put(KEY_DURATION_MILLIS, durationMillis);
        return jsonObject;
    }

    public static SessionRoleInterval fromJson(JSONObject jsonObject) {
        SpeakerRole role;
        try {
            role = SpeakerRole.valueOf(jsonObject.optString(KEY_ROLE, SpeakerRole.SILENCE.name()));
        } catch (IllegalArgumentException exception) {
            role = SpeakerRole.SILENCE;
        }
        long startOffsetMillis = jsonObject.optLong(KEY_START_OFFSET_MILLIS, 0L);
        long endOffsetMillis = jsonObject.optLong(KEY_END_OFFSET_MILLIS, startOffsetMillis);
        return new SessionRoleInterval(
                role,
                startOffsetMillis,
                endOffsetMillis,
                jsonObject.optLong(KEY_DURATION_MILLIS, Math.max(0L, endOffsetMillis - startOffsetMillis))
        );
    }
}
