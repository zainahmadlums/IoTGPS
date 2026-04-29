package com.example.audio.data;

import org.json.JSONException;
import org.json.JSONObject;

public final class InstructorVoiceProfile {

    private static final String KEY_PROFILE_ID = "profileId";
    private static final String KEY_TITLE = "title";
    private static final String KEY_METADATA_FILE_NAME = "metadataFileName";
    private static final String KEY_AUDIO_FILE_NAME = "audioFileName";
    private static final String KEY_PROMPT_TEXT = "promptText";
    private static final String KEY_START_TIME_MILLIS = "startTimeMillis";
    private static final String KEY_END_TIME_MILLIS = "endTimeMillis";
    private static final String KEY_DURATION_MILLIS = "durationMillis";
    private static final String KEY_SAMPLE_RATE_HZ = "sampleRateHz";
    private static final String KEY_CHANNEL_COUNT = "channelCount";
    private static final String KEY_FRAME_SIZE_SAMPLES = "frameSizeSamples";
    private static final String KEY_FRAME_COUNT = "frameCount";
    private static final String KEY_AUDIO_FILE_SIZE_BYTES = "audioFileSizeBytes";
    private static final String KEY_AVERAGE_RMS = "averageRms";
    private static final String KEY_AVERAGE_ZCR = "averageZcr";
    private static final String KEY_AVERAGE_LOW_BAND_RATIO = "averageLowBandRatio";
    private static final String KEY_AVERAGE_HIGH_BAND_RATIO = "averageHighBandRatio";

    private final String profileId;
    private final String title;
    private final String metadataFileName;
    private final String audioFileName;
    private final String promptText;
    private final long startTimeMillis;
    private final long endTimeMillis;
    private final long durationMillis;
    private final int sampleRateHz;
    private final int channelCount;
    private final int frameSizeSamples;
    private final int frameCount;
    private final long audioFileSizeBytes;
    private final float averageRms;
    private final float averageZcr;
    private final float averageLowBandRatio;
    private final float averageHighBandRatio;

    public InstructorVoiceProfile(
            String profileId,
            String title,
            String metadataFileName,
            String audioFileName,
            String promptText,
            long startTimeMillis,
            long endTimeMillis,
            long durationMillis,
            int sampleRateHz,
            int channelCount,
            int frameSizeSamples,
            int frameCount,
            long audioFileSizeBytes,
            float averageRms,
            float averageZcr,
            float averageLowBandRatio,
            float averageHighBandRatio
    ) {
        this.profileId = profileId;
        this.title = title;
        this.metadataFileName = metadataFileName;
        this.audioFileName = audioFileName;
        this.promptText = promptText;
        this.startTimeMillis = startTimeMillis;
        this.endTimeMillis = endTimeMillis;
        this.durationMillis = durationMillis;
        this.sampleRateHz = sampleRateHz;
        this.channelCount = channelCount;
        this.frameSizeSamples = frameSizeSamples;
        this.frameCount = frameCount;
        this.audioFileSizeBytes = audioFileSizeBytes;
        this.averageRms = averageRms;
        this.averageZcr = averageZcr;
        this.averageLowBandRatio = averageLowBandRatio;
        this.averageHighBandRatio = averageHighBandRatio;
    }

    public String getProfileId() {
        return profileId;
    }

    public String getTitle() {
        return title;
    }

    public String getMetadataFileName() {
        return metadataFileName;
    }

    public String getAudioFileName() {
        return audioFileName;
    }

    public String getPromptText() {
        return promptText;
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

    public int getSampleRateHz() {
        return sampleRateHz;
    }

    public int getChannelCount() {
        return channelCount;
    }

    public int getFrameSizeSamples() {
        return frameSizeSamples;
    }

    public int getFrameCount() {
        return frameCount;
    }

    public long getAudioFileSizeBytes() {
        return audioFileSizeBytes;
    }

    public float getAverageRms() {
        return averageRms;
    }

    public float getAverageZcr() {
        return averageZcr;
    }

    public float getAverageLowBandRatio() {
        return averageLowBandRatio;
    }

    public float getAverageHighBandRatio() {
        return averageHighBandRatio;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject jsonObject = new JSONObject();
        jsonObject.put(KEY_PROFILE_ID, profileId);
        jsonObject.put(KEY_TITLE, title);
        jsonObject.put(KEY_METADATA_FILE_NAME, metadataFileName);
        jsonObject.put(KEY_AUDIO_FILE_NAME, audioFileName);
        jsonObject.put(KEY_PROMPT_TEXT, promptText);
        jsonObject.put(KEY_START_TIME_MILLIS, startTimeMillis);
        jsonObject.put(KEY_END_TIME_MILLIS, endTimeMillis);
        jsonObject.put(KEY_DURATION_MILLIS, durationMillis);
        jsonObject.put(KEY_SAMPLE_RATE_HZ, sampleRateHz);
        jsonObject.put(KEY_CHANNEL_COUNT, channelCount);
        jsonObject.put(KEY_FRAME_SIZE_SAMPLES, frameSizeSamples);
        jsonObject.put(KEY_FRAME_COUNT, frameCount);
        jsonObject.put(KEY_AUDIO_FILE_SIZE_BYTES, audioFileSizeBytes);
        jsonObject.put(KEY_AVERAGE_RMS, averageRms);
        jsonObject.put(KEY_AVERAGE_ZCR, averageZcr);
        jsonObject.put(KEY_AVERAGE_LOW_BAND_RATIO, averageLowBandRatio);
        jsonObject.put(KEY_AVERAGE_HIGH_BAND_RATIO, averageHighBandRatio);
        return jsonObject;
    }

    public static InstructorVoiceProfile fromJson(JSONObject jsonObject) throws JSONException {
        return new InstructorVoiceProfile(
                jsonObject.getString(KEY_PROFILE_ID),
                jsonObject.optString(KEY_TITLE, "Instructor Voice Setup"),
                jsonObject.getString(KEY_METADATA_FILE_NAME),
                jsonObject.getString(KEY_AUDIO_FILE_NAME),
                jsonObject.optString(KEY_PROMPT_TEXT, ""),
                jsonObject.getLong(KEY_START_TIME_MILLIS),
                jsonObject.getLong(KEY_END_TIME_MILLIS),
                jsonObject.getLong(KEY_DURATION_MILLIS),
                jsonObject.getInt(KEY_SAMPLE_RATE_HZ),
                jsonObject.getInt(KEY_CHANNEL_COUNT),
                jsonObject.getInt(KEY_FRAME_SIZE_SAMPLES),
                jsonObject.getInt(KEY_FRAME_COUNT),
                jsonObject.optLong(KEY_AUDIO_FILE_SIZE_BYTES, 0L),
                (float) jsonObject.optDouble(KEY_AVERAGE_RMS, 0.0d),
                (float) jsonObject.optDouble(KEY_AVERAGE_ZCR, 0.0d),
                (float) jsonObject.optDouble(KEY_AVERAGE_LOW_BAND_RATIO, 0.0d),
                (float) jsonObject.optDouble(KEY_AVERAGE_HIGH_BAND_RATIO, 0.0d)
        );
    }
}
