package com.example.audio.ui.library;

import com.example.audio.reverb.ReverbResult;

import java.io.Serializable;

public class AudioSessionItem implements Serializable {

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
    private final String audioFileName;
    private final boolean playbackAvailable;
    private final boolean sampleData;

    public AudioSessionItem(
            String id,
            String title,
            String generatedFilename,
            long startTimeMillis,
            long endTimeMillis,
            long durationMillis,
            long fileSizeBytes,
            float speechRatio,
            int disturbanceCount,
            ReverbResult.Level reverbLevel,
            String audioFileName,
            boolean playbackAvailable,
            boolean sampleData
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
        this.audioFileName = audioFileName;
        this.playbackAvailable = playbackAvailable;
        this.sampleData = sampleData;
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

    public String getAudioFileName() {
        return audioFileName;
    }

    public boolean isPlaybackAvailable() {
        return playbackAvailable;
    }

    public boolean isSampleData() {
        return sampleData;
    }
}
