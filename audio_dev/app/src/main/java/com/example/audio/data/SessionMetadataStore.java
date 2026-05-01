package com.example.audio.data;

import android.content.Context;

import com.example.audio.audio.AudioConfig;
import com.example.audio.util.Logger;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class SessionMetadataStore {

    private static final String TAG = "SessionMetadataStore";
    private static final long MERGE_GAP_MILLIS = 200L;
    private static final long MIN_INTERVAL_MILLIS = 160L;
    private static final SessionMetadataStore INSTANCE = new SessionMetadataStore();

    private SessionMetadataStore() {
    }

    public static SessionMetadataStore getInstance() {
        return INSTANCE;
    }

    public SessionMetadata buildMetadata(
            String sessionId,
            String title,
            String metadataFileName,
            String rawAudioFileName,
            String conditionedAudioFileName,
            String instructorProfileMetadataFileName,
            String instructorProfileAudioFileName,
            long startTimeMillis,
            long endTimeMillis,
            float speakingRatio,
            int disturbanceCount,
            com.example.audio.reverb.ReverbResult.Level reverbLevel,
            List<SpeechEvent> speechEvents,
            AudioConfig audioConfig,
            List<SessionDiarizationChunk> diarizationChunks
    ) {
        long durationMillis = Math.max(1_000L, endTimeMillis - startTimeMillis);
        List<SessionSpeechInterval> intervals = buildSpeechIntervals(
                speechEvents,
                startTimeMillis,
                durationMillis,
                audioConfig != null ? audioConfig.getFrameDurationMs() : AudioConfig.SILERO_FRAME_DURATION_MS
        );
        List<SessionRoleInterval> roleIntervals = buildRoleIntervals(
                speechEvents,
                startTimeMillis,
                durationMillis,
                audioConfig != null ? audioConfig.getFrameDurationMs() : AudioConfig.SILERO_FRAME_DURATION_MS
        );
        long totalSpeechMillis = 0L;
        long longestSpeechMillis = 0L;
        for (SessionSpeechInterval interval : intervals) {
            totalSpeechMillis += interval.getDurationMillis();
            longestSpeechMillis = Math.max(longestSpeechMillis, interval.getDurationMillis());
        }
        long totalSilenceMillis = Math.max(0L, durationMillis - totalSpeechMillis);
        long totalInstructorMillis = totalRoleMillis(roleIntervals, SpeakerRole.INSTRUCTOR);
        long totalStudentMillis = totalRoleMillis(roleIntervals, SpeakerRole.STUDENT);
        long totalBothMillis = totalRoleMillis(roleIntervals, SpeakerRole.BOTH);
        float normalizedSpeakingRatio = durationMillis == 0L
                ? speakingRatio
                : (float) totalSpeechMillis / durationMillis;

        return new SessionMetadata(
                sessionId,
                title,
                metadataFileName,
                startTimeMillis,
                endTimeMillis,
                durationMillis,
                totalSpeechMillis,
                totalSilenceMillis,
                totalInstructorMillis,
                totalStudentMillis,
                totalBothMillis,
                normalizedSpeakingRatio,
                disturbanceCount,
                reverbLevel,
                intervals.size(),
                longestSpeechMillis,
                intervals,
                roleIntervals,
                "COMPLETE",
                "android-local-speaker-state",
                diarizationChunks != null ? diarizationChunks : new ArrayList<>(),
                rawAudioFileName,
                conditionedAudioFileName,
                instructorProfileMetadataFileName,
                instructorProfileAudioFileName
        );
    }

    public long writeMetadata(Context context, SessionMetadata sessionMetadata) {
        File outputFile = SessionMetadataFileManager.resolveMetadataFile(
                context.getApplicationContext(),
                sessionMetadata.getMetadataFileName()
        );
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputFile, false))) {
            writer.write(sessionMetadata.toJson().toString());
            return outputFile.length();
        } catch (IOException | JSONException exception) {
            throw new IllegalStateException("Failed to persist session metadata.", exception);
        }
    }

    public SessionMetadata readMetadata(Context context, String metadataFileName) {
        File inputFile = SessionMetadataFileManager.resolveMetadataFile(
                context.getApplicationContext(),
                metadataFileName
        );
        if (!inputFile.exists()) {
            return null;
        }

        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(inputFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
            return SessionMetadata.fromJson(new JSONObject(builder.toString()));
        } catch (IOException | JSONException exception) {
            Logger.e(TAG, "Failed to read session metadata: " + metadataFileName, exception);
            return null;
        }
    }

    public SessionMetadata renameMetadata(
            Context context,
            String currentMetadataFileName,
            String updatedMetadataFileName,
            String updatedTitle,
            String updatedRawAudioFileName,
            String updatedConditionedAudioFileName
    ) {
        SessionMetadata existingMetadata = readMetadata(context, updatedMetadataFileName);
        if (existingMetadata == null) {
            existingMetadata = readMetadata(context, currentMetadataFileName);
        }
        if (existingMetadata == null) {
            return null;
        }

        SessionMetadata renamedMetadata = new SessionMetadata(
                existingMetadata.getSessionId(),
                updatedTitle,
                updatedMetadataFileName,
                existingMetadata.getStartTimeMillis(),
                existingMetadata.getEndTimeMillis(),
                existingMetadata.getDurationMillis(),
                existingMetadata.getTotalSpeechMillis(),
                existingMetadata.getTotalSilenceMillis(),
                existingMetadata.getTotalInstructorMillis(),
                existingMetadata.getTotalStudentMillis(),
                existingMetadata.getTotalBothMillis(),
                existingMetadata.getSpeakingRatio(),
                existingMetadata.getDisturbanceCount(),
                existingMetadata.getReverbLevel(),
                existingMetadata.getIntervalCount(),
                existingMetadata.getLongestSpeechMillis(),
                existingMetadata.getSpeechIntervals(),
                existingMetadata.getRoleIntervals(),
                existingMetadata.getDiarizationStatus(),
                existingMetadata.getDiarizationEngine(),
                existingMetadata.getDiarizationChunks(),
                updatedRawAudioFileName,
                updatedConditionedAudioFileName,
                existingMetadata.getInstructorProfileMetadataFileName(),
                existingMetadata.getInstructorProfileAudioFileName()
        );
        writeMetadata(context, renamedMetadata);
        if (!currentMetadataFileName.equals(updatedMetadataFileName)) {
            SessionMetadataFileManager.deleteMetadataFile(context, currentMetadataFileName);
        }
        return renamedMetadata;
    }

    private List<SessionSpeechInterval> buildSpeechIntervals(
            List<SpeechEvent> speechEvents,
            long sessionStartTimeMillis,
            long sessionDurationMillis,
            long frameDurationMillis
    ) {
        List<SessionSpeechInterval> intervals = new ArrayList<>();
        if (speechEvents == null || speechEvents.isEmpty()) {
            return intervals;
        }

        long currentStart = -1L;
        long currentEnd = -1L;
        for (SpeechEvent speechEvent : speechEvents) {
            if (!speechEvent.isSpeech()) {
                continue;
            }

            long eventStart = Math.max(0L, speechEvent.getTimestampMillis() - sessionStartTimeMillis);
            long eventEnd = Math.min(sessionDurationMillis, eventStart + frameDurationMillis);
            if (currentStart < 0L) {
                currentStart = eventStart;
                currentEnd = eventEnd;
                continue;
            }

            if (eventStart <= currentEnd + MERGE_GAP_MILLIS) {
                currentEnd = Math.max(currentEnd, eventEnd);
                continue;
            }

            maybeAddInterval(intervals, currentStart, currentEnd);
            currentStart = eventStart;
            currentEnd = eventEnd;
        }

        maybeAddInterval(intervals, currentStart, currentEnd);
        return intervals;
    }

    private List<SessionRoleInterval> buildRoleIntervals(
            List<SpeechEvent> speechEvents,
            long sessionStartTimeMillis,
            long sessionDurationMillis,
            long frameDurationMillis
    ) {
        List<SessionRoleInterval> intervals = new ArrayList<>();
        if (speechEvents == null || speechEvents.isEmpty()) {
            return intervals;
        }

        SpeakerRole currentRole = null;
        long currentStart = -1L;
        long currentEnd = -1L;
        for (SpeechEvent speechEvent : speechEvents) {
            SpeakerRole role = speechEvent.isSpeech()
                    ? speechEvent.getSpeakerRole()
                    : SpeakerRole.SILENCE;
            long eventStart = Math.max(0L, speechEvent.getTimestampMillis() - sessionStartTimeMillis);
            long eventEnd = Math.min(sessionDurationMillis, eventStart + frameDurationMillis);

            if (currentRole == null) {
                currentRole = role;
                currentStart = eventStart;
                currentEnd = eventEnd;
                continue;
            }

            if (role == currentRole && eventStart <= currentEnd + MERGE_GAP_MILLIS) {
                currentEnd = Math.max(currentEnd, eventEnd);
                continue;
            }

            maybeAddRoleInterval(intervals, currentRole, currentStart, currentEnd);
            currentRole = role;
            currentStart = eventStart;
            currentEnd = eventEnd;
        }

        maybeAddRoleInterval(intervals, currentRole, currentStart, currentEnd);
        return intervals;
    }

    private long totalRoleMillis(List<SessionRoleInterval> intervals, SpeakerRole speakerRole) {
        long totalMillis = 0L;
        for (SessionRoleInterval interval : intervals) {
            if (interval.getRole() == speakerRole) {
                totalMillis += interval.getDurationMillis();
            }
        }
        return totalMillis;
    }

    private void maybeAddInterval(
            List<SessionSpeechInterval> intervals,
            long startOffsetMillis,
            long endOffsetMillis
    ) {
        if (startOffsetMillis < 0L || endOffsetMillis <= startOffsetMillis) {
            return;
        }

        long durationMillis = endOffsetMillis - startOffsetMillis;
        if (durationMillis < MIN_INTERVAL_MILLIS) {
            return;
        }
        intervals.add(new SessionSpeechInterval(startOffsetMillis, endOffsetMillis, durationMillis));
    }

    private void maybeAddRoleInterval(
            List<SessionRoleInterval> intervals,
            SpeakerRole role,
            long startOffsetMillis,
            long endOffsetMillis
    ) {
        if (role == null || startOffsetMillis < 0L || endOffsetMillis <= startOffsetMillis) {
            return;
        }

        long durationMillis = endOffsetMillis - startOffsetMillis;
        if (durationMillis < MIN_INTERVAL_MILLIS) {
            return;
        }
        intervals.add(new SessionRoleInterval(role, startOffsetMillis, endOffsetMillis, durationMillis));
    }
}
