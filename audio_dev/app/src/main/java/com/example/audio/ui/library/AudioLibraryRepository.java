package com.example.audio.ui.library;

import com.example.audio.data.SessionRepository;
import com.example.audio.data.SpeechEvent;
import com.example.audio.pipeline.SessionSummary;
import com.example.audio.reverb.ReverbResult;
import com.example.audio.util.TimeUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class AudioLibraryRepository {

    private static final AudioLibraryRepository INSTANCE = new AudioLibraryRepository();

    private AudioLibraryRepository() {
    }

    public static AudioLibraryRepository getInstance() {
        return INSTANCE;
    }

    public List<AudioSessionItem> getSessions() {
        List<AudioSessionItem> items = new ArrayList<>();
        appendLiveSnapshot(items);
        appendSampleSessions(items);
        return items;
    }

    private void appendLiveSnapshot(List<AudioSessionItem> items) {
        SessionRepository repository = SessionRepository.getInstance();
        List<SpeechEvent> speechEvents = repository.getSpeechEvents();
        if (speechEvents.isEmpty() && !repository.isSessionRunning()) {
            return;
        }

        long endTimeMillis = speechEvents.isEmpty()
                ? TimeUtils.nowMillis()
                : speechEvents.get(speechEvents.size() - 1).getTimestampMillis();
        long startTimeMillis = speechEvents.isEmpty()
                ? endTimeMillis - 8 * 60_000L
                : speechEvents.get(0).getTimestampMillis();
        long durationMillis = Math.max(60_000L, endTimeMillis - startTimeMillis);
        SessionSummary sessionSummary = repository.getSessionSummary();
        float speechRatio = sessionSummary == null ? 0.0f : sessionSummary.getSpeakingRatio();
        int disturbanceCount = sessionSummary == null ? 0 : sessionSummary.getDisturbanceCount();
        ReverbResult.Level reverbLevel = sessionSummary == null
                ? ReverbResult.Level.LOW
                : sessionSummary.getCoarseReverbLevel();

        items.add(new AudioSessionItem(
                "live-session",
                repository.isSessionRunning() ? "Live Session Snapshot" : "Latest Session Snapshot",
                String.format(Locale.US, "deployteach_%1$tY%1$tm%1$td_%1$tH%1$tM.aud", endTimeMillis),
                startTimeMillis,
                endTimeMillis,
                durationMillis,
                estimateFileSizeBytes(durationMillis),
                speechRatio,
                disturbanceCount,
                reverbLevel,
                false,
                true
        ));
    }

    private void appendSampleSessions(List<AudioSessionItem> items) {
        long now = TimeUtils.nowMillis();
        items.add(new AudioSessionItem(
                "sample-aurora-lecture",
                "Aurora Lecture Pass",
                "deployteach_aurora_lecture_01.aud",
                now - 42 * 60_000L,
                now - 12 * 60_000L,
                30 * 60_000L,
                24_800_000L,
                0.72f,
                3,
                ReverbResult.Level.LOW,
                false,
                true
        ));
        items.add(new AudioSessionItem(
                "sample-studio-a",
                "Studio A Office Hours",
                "deployteach_studio_a_02.aud",
                now - 5 * 60 * 60_000L,
                now - 4 * 60 * 60_000L - 18 * 60_000L,
                42 * 60_000L,
                31_100_000L,
                0.58f,
                7,
                ReverbResult.Level.MEDIUM,
                false,
                true
        ));
        items.add(new AudioSessionItem(
                "sample-seminar",
                "Seminar Hall Rehearsal",
                "deployteach_seminar_hall_03.aud",
                now - 24 * 60 * 60_000L,
                now - 23 * 60 * 60_000L - 11 * 60_000L,
                49 * 60_000L,
                40_400_000L,
                0.81f,
                2,
                ReverbResult.Level.HIGH,
                false,
                true
        ));
        items.add(new AudioSessionItem(
                "sample-lab",
                "Research Lab Sync",
                "deployteach_research_lab_04.aud",
                now - 2 * 24 * 60 * 60_000L,
                now - 2 * 24 * 60 * 60_000L + 27 * 60_000L,
                27 * 60_000L,
                19_500_000L,
                0.44f,
                12,
                ReverbResult.Level.MEDIUM,
                false,
                true
        ));
        items.add(new AudioSessionItem(
                "sample-briefing",
                "Evening Briefing",
                "deployteach_evening_briefing_05.aud",
                now - 5 * 24 * 60 * 60_000L,
                now - 5 * 24 * 60 * 60_000L + 18 * 60_000L,
                18 * 60_000L,
                12_400_000L,
                0.63f,
                1,
                ReverbResult.Level.LOW,
                false,
                true
        ));
    }

    private long estimateFileSizeBytes(long durationMillis) {
        long seconds = Math.max(60L, durationMillis / 1000L);
        return seconds * 96_000L;
    }
}
