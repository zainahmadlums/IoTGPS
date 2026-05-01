package com.example.audio.data;

import com.example.audio.reverb.ReverbResult;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class SessionMetadata {

    private static final String KEY_SESSION_ID = "sessionId";
    private static final String KEY_PRIVACY_MODE = "privacyMode";
    private static final String PRIVACY_MODE_LOCAL_ONLY = "local_only";
    private static final String KEY_TITLE = "title";
    private static final String KEY_METADATA_FILE_NAME = "metadataFileName";
    private static final String KEY_START_TIME_MILLIS = "startTimeMillis";
    private static final String KEY_END_TIME_MILLIS = "endTimeMillis";
    private static final String KEY_DURATION_MILLIS = "durationMillis";
    private static final String KEY_TOTAL_SPEECH_MILLIS = "totalSpeechMillis";
    private static final String KEY_TOTAL_SILENCE_MILLIS = "totalSilenceMillis";
    private static final String KEY_TOTAL_INSTRUCTOR_MILLIS = "totalInstructorMillis";
    private static final String KEY_TOTAL_STUDENT_MILLIS = "totalStudentMillis";
    private static final String KEY_TOTAL_BOTH_MILLIS = "totalBothMillis";
    private static final String KEY_SPEAKING_RATIO = "speakingRatio";
    private static final String KEY_DISTURBANCE_COUNT = "disturbanceCount";
    private static final String KEY_REVERB_LEVEL = "reverbLevel";
    private static final String KEY_INTERVAL_COUNT = "intervalCount";
    private static final String KEY_LONGEST_SPEECH_MILLIS = "longestSpeechMillis";
    private static final String KEY_SPEECH_INTERVALS = "speechIntervals";
    private static final String KEY_ROLE_INTERVALS = "roleIntervals";
    private static final String KEY_DIARIZATION_STATUS = "diarizationStatus";
    private static final String KEY_DIARIZATION_ENGINE = "diarizationEngine";
    private static final String KEY_DIARIZATION_CHUNKS = "diarizationChunks";
    private static final String KEY_RAW_AUDIO_FILE_NAME = "rawAudioFileName";
    private static final String KEY_CONDITIONED_AUDIO_FILE_NAME = "conditionedAudioFileName";
    private static final String KEY_INSTRUCTOR_PROFILE_METADATA_FILE_NAME = "instructorProfileMetadataFileName";
    private static final String KEY_INSTRUCTOR_PROFILE_AUDIO_FILE_NAME = "instructorProfileAudioFileName";

    private final String sessionId;
    private final String title;
    private final String metadataFileName;
    private final long startTimeMillis;
    private final long endTimeMillis;
    private final long durationMillis;
    private final long totalSpeechMillis;
    private final long totalSilenceMillis;
    private final long totalInstructorMillis;
    private final long totalStudentMillis;
    private final long totalBothMillis;
    private final float speakingRatio;
    private final int disturbanceCount;
    private final ReverbResult.Level reverbLevel;
    private final int intervalCount;
    private final long longestSpeechMillis;
    private final List<SessionSpeechInterval> speechIntervals;
    private final List<SessionRoleInterval> roleIntervals;
    private final String diarizationStatus;
    private final String diarizationEngine;
    private final List<SessionDiarizationChunk> diarizationChunks;
    private final String rawAudioFileName;
    private final String conditionedAudioFileName;
    private final String instructorProfileMetadataFileName;
    private final String instructorProfileAudioFileName;

    public SessionMetadata(
            String sessionId,
            String title,
            String metadataFileName,
            long startTimeMillis,
            long endTimeMillis,
            long durationMillis,
            long totalSpeechMillis,
            long totalSilenceMillis,
            long totalInstructorMillis,
            long totalStudentMillis,
            long totalBothMillis,
            float speakingRatio,
            int disturbanceCount,
            ReverbResult.Level reverbLevel,
            int intervalCount,
            long longestSpeechMillis,
            List<SessionSpeechInterval> speechIntervals,
            List<SessionRoleInterval> roleIntervals,
            String diarizationStatus,
            String diarizationEngine,
            List<SessionDiarizationChunk> diarizationChunks,
            String rawAudioFileName,
            String conditionedAudioFileName,
            String instructorProfileMetadataFileName,
            String instructorProfileAudioFileName
    ) {
        this.sessionId = sessionId;
        this.title = title;
        this.metadataFileName = metadataFileName;
        this.startTimeMillis = startTimeMillis;
        this.endTimeMillis = endTimeMillis;
        this.durationMillis = durationMillis;
        this.totalSpeechMillis = totalSpeechMillis;
        this.totalSilenceMillis = totalSilenceMillis;
        this.totalInstructorMillis = totalInstructorMillis;
        this.totalStudentMillis = totalStudentMillis;
        this.totalBothMillis = totalBothMillis;
        this.speakingRatio = speakingRatio;
        this.disturbanceCount = disturbanceCount;
        this.reverbLevel = reverbLevel;
        this.intervalCount = intervalCount;
        this.longestSpeechMillis = longestSpeechMillis;
        this.speechIntervals = Collections.unmodifiableList(new ArrayList<>(
                speechIntervals != null ? speechIntervals : Collections.emptyList()
        ));
        this.roleIntervals = Collections.unmodifiableList(new ArrayList<>(
                roleIntervals != null ? roleIntervals : Collections.emptyList()
        ));
        this.diarizationStatus = diarizationStatus == null ? "PENDING" : diarizationStatus;
        this.diarizationEngine = diarizationEngine;
        this.diarizationChunks = Collections.unmodifiableList(new ArrayList<>(
                diarizationChunks != null ? diarizationChunks : Collections.emptyList()
        ));
        this.rawAudioFileName = rawAudioFileName;
        this.conditionedAudioFileName = conditionedAudioFileName;
        this.instructorProfileMetadataFileName = instructorProfileMetadataFileName;
        this.instructorProfileAudioFileName = instructorProfileAudioFileName;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getTitle() {
        return title;
    }

    public String getMetadataFileName() {
        return metadataFileName;
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

    public long getTotalSpeechMillis() {
        return totalSpeechMillis;
    }

    public long getTotalSilenceMillis() {
        return totalSilenceMillis;
    }

    public long getTotalInstructorMillis() {
        return totalInstructorMillis;
    }

    public long getTotalStudentMillis() {
        return totalStudentMillis;
    }

    public long getTotalBothMillis() {
        return totalBothMillis;
    }

    public float getSpeakingRatio() {
        return speakingRatio;
    }

    public int getDisturbanceCount() {
        return disturbanceCount;
    }

    public ReverbResult.Level getReverbLevel() {
        return reverbLevel;
    }

    public int getIntervalCount() {
        return intervalCount;
    }

    public long getLongestSpeechMillis() {
        return longestSpeechMillis;
    }

    public List<SessionSpeechInterval> getSpeechIntervals() {
        return speechIntervals;
    }

    public List<SessionRoleInterval> getRoleIntervals() {
        return roleIntervals;
    }

    public String getDiarizationStatus() {
        return diarizationStatus;
    }

    public String getDiarizationEngine() {
        return diarizationEngine;
    }

    public List<SessionDiarizationChunk> getDiarizationChunks() {
        return diarizationChunks;
    }

    public String getRawAudioFileName() {
        return rawAudioFileName;
    }

    public String getConditionedAudioFileName() {
        return conditionedAudioFileName;
    }

    public String getInstructorProfileMetadataFileName() {
        return instructorProfileMetadataFileName;
    }

    public String getInstructorProfileAudioFileName() {
        return instructorProfileAudioFileName;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject jsonObject = new JSONObject();
        jsonObject.put(KEY_SESSION_ID, sessionId);
        jsonObject.put(KEY_PRIVACY_MODE, PRIVACY_MODE_LOCAL_ONLY);
        jsonObject.put(KEY_TITLE, title);
        jsonObject.put(KEY_METADATA_FILE_NAME, metadataFileName);
        jsonObject.put(KEY_START_TIME_MILLIS, startTimeMillis);
        jsonObject.put(KEY_END_TIME_MILLIS, endTimeMillis);
        jsonObject.put(KEY_DURATION_MILLIS, durationMillis);
        jsonObject.put(KEY_TOTAL_SPEECH_MILLIS, totalSpeechMillis);
        jsonObject.put(KEY_TOTAL_SILENCE_MILLIS, totalSilenceMillis);
        jsonObject.put(KEY_TOTAL_INSTRUCTOR_MILLIS, totalInstructorMillis);
        jsonObject.put(KEY_TOTAL_STUDENT_MILLIS, totalStudentMillis);
        jsonObject.put(KEY_TOTAL_BOTH_MILLIS, totalBothMillis);
        jsonObject.put(KEY_SPEAKING_RATIO, speakingRatio);
        jsonObject.put(KEY_DISTURBANCE_COUNT, disturbanceCount);
        jsonObject.put(KEY_REVERB_LEVEL, reverbLevel.name());
        jsonObject.put(KEY_INTERVAL_COUNT, intervalCount);
        jsonObject.put(KEY_LONGEST_SPEECH_MILLIS, longestSpeechMillis);

        JSONArray intervalsJson = new JSONArray();
        for (SessionSpeechInterval speechInterval : speechIntervals) {
            intervalsJson.put(speechInterval.toJson());
        }
        jsonObject.put(KEY_SPEECH_INTERVALS, intervalsJson);
        JSONArray roleIntervalsJson = new JSONArray();
        for (SessionRoleInterval roleInterval : roleIntervals) {
            roleIntervalsJson.put(roleInterval.toJson());
        }
        jsonObject.put(KEY_ROLE_INTERVALS, roleIntervalsJson);
        jsonObject.put(KEY_DIARIZATION_STATUS, diarizationStatus);
        jsonObject.put(KEY_DIARIZATION_ENGINE, diarizationEngine);
        JSONArray diarizationChunksJson = new JSONArray();
        for (SessionDiarizationChunk chunk : diarizationChunks) {
            diarizationChunksJson.put(chunk.toJson());
        }
        jsonObject.put(KEY_DIARIZATION_CHUNKS, diarizationChunksJson);
        jsonObject.put(KEY_RAW_AUDIO_FILE_NAME, rawAudioFileName);
        jsonObject.put(KEY_CONDITIONED_AUDIO_FILE_NAME, conditionedAudioFileName);
        jsonObject.put(KEY_INSTRUCTOR_PROFILE_METADATA_FILE_NAME, instructorProfileMetadataFileName);
        jsonObject.put(KEY_INSTRUCTOR_PROFILE_AUDIO_FILE_NAME, instructorProfileAudioFileName);
        return jsonObject;
    }

    public static SessionMetadata fromJson(JSONObject jsonObject) throws JSONException {
        JSONArray intervalsJson = jsonObject.optJSONArray(KEY_SPEECH_INTERVALS);
        List<SessionSpeechInterval> speechIntervals = new ArrayList<>();
        if (intervalsJson != null) {
            for (int index = 0; index < intervalsJson.length(); index++) {
                JSONObject intervalJson = intervalsJson.optJSONObject(index);
                if (intervalJson != null) {
                    speechIntervals.add(SessionSpeechInterval.fromJson(intervalJson));
                }
            }
        }
        JSONArray roleIntervalsJson = jsonObject.optJSONArray(KEY_ROLE_INTERVALS);
        List<SessionRoleInterval> roleIntervals = new ArrayList<>();
        if (roleIntervalsJson != null) {
            for (int index = 0; index < roleIntervalsJson.length(); index++) {
                JSONObject intervalJson = roleIntervalsJson.optJSONObject(index);
                if (intervalJson != null) {
                    roleIntervals.add(SessionRoleInterval.fromJson(intervalJson));
                }
            }
        }
        JSONArray diarizationChunksJson = jsonObject.optJSONArray(KEY_DIARIZATION_CHUNKS);
        List<SessionDiarizationChunk> diarizationChunks = new ArrayList<>();
        if (diarizationChunksJson != null) {
            for (int index = 0; index < diarizationChunksJson.length(); index++) {
                JSONObject chunkJson = diarizationChunksJson.optJSONObject(index);
                if (chunkJson != null) {
                    diarizationChunks.add(SessionDiarizationChunk.fromJson(chunkJson));
                }
            }
        }

        return new SessionMetadata(
                jsonObject.getString(KEY_SESSION_ID),
                jsonObject.getString(KEY_TITLE),
                jsonObject.getString(KEY_METADATA_FILE_NAME),
                jsonObject.getLong(KEY_START_TIME_MILLIS),
                jsonObject.getLong(KEY_END_TIME_MILLIS),
                jsonObject.getLong(KEY_DURATION_MILLIS),
                jsonObject.optLong(KEY_TOTAL_SPEECH_MILLIS, 0L),
                jsonObject.optLong(KEY_TOTAL_SILENCE_MILLIS, 0L),
                jsonObject.optLong(KEY_TOTAL_INSTRUCTOR_MILLIS, 0L),
                jsonObject.optLong(KEY_TOTAL_STUDENT_MILLIS, 0L),
                jsonObject.optLong(KEY_TOTAL_BOTH_MILLIS, 0L),
                (float) jsonObject.optDouble(KEY_SPEAKING_RATIO, 0.0d),
                jsonObject.optInt(KEY_DISTURBANCE_COUNT, 0),
                ReverbResult.Level.valueOf(
                        jsonObject.optString(KEY_REVERB_LEVEL, ReverbResult.Level.LOW.name())
                ),
                jsonObject.optInt(KEY_INTERVAL_COUNT, speechIntervals.size()),
                jsonObject.optLong(KEY_LONGEST_SPEECH_MILLIS, 0L),
                speechIntervals,
                roleIntervals,
                jsonObject.optString(KEY_DIARIZATION_STATUS, roleIntervals.isEmpty() ? "PENDING" : "COMPLETE"),
                jsonObject.optString(KEY_DIARIZATION_ENGINE, roleIntervals.isEmpty() ? null : "legacy"),
                diarizationChunks,
                jsonObject.optString(KEY_RAW_AUDIO_FILE_NAME, null),
                jsonObject.optString(KEY_CONDITIONED_AUDIO_FILE_NAME, null),
                jsonObject.optString(KEY_INSTRUCTOR_PROFILE_METADATA_FILE_NAME, null),
                jsonObject.optString(KEY_INSTRUCTOR_PROFILE_AUDIO_FILE_NAME, null)
        );
    }
}
