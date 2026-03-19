package com.example.audio.data;

import android.os.Handler;
import android.os.Looper;

import com.example.audio.pipeline.FrameAnalysisResult;
import com.example.audio.pipeline.SessionAggregator;
import com.example.audio.pipeline.SessionSummary;
import com.example.audio.reverb.ReverbResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class SessionRepository {

    public interface SpeechStateListener {
        void onSpeechStateChanged(SpeechEvent speechEvent);
    }

    private static final SessionRepository INSTANCE = new SessionRepository();

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<SpeechEvent> speechEvents = new ArrayList<>();
    private final SessionAggregator sessionAggregator = new SessionAggregator();
    private final CopyOnWriteArrayList<SpeechStateListener> listeners = new CopyOnWriteArrayList<>();
    private SpeechEvent latestSpeechEvent;
    private SessionSummary latestSessionSummary = new SessionSummary(
            0.0f,
            0,
            ReverbResult.Level.LOW,
            false
    );
    private boolean sessionRunning;

    private SessionRepository() {
    }

    public static SessionRepository getInstance() {
        return INSTANCE;
    }

    public synchronized void append(FrameAnalysisResult result) {
        if (result == null || result.getVadResult() == null) {
            return;
        }

        SpeechEvent speechEvent = new SpeechEvent(
                result.getVadResult().getTimestampMillis(),
                result.getVadResult().isSpeech(),
                result.getDisturbanceResult() != null
                        && result.getDisturbanceResult().isDisturbanceDetected(),
                result.getReverbResult() != null
                        ? result.getReverbResult().getLevel()
                        : ReverbResult.Level.LOW
        );
        speechEvents.add(speechEvent);
        sessionAggregator.add(result);
        latestSessionSummary = sessionAggregator.getSummary();
        latestSpeechEvent = speechEvent;
        notifyListeners(speechEvent);
    }

    public synchronized SpeechEvent getLatestSpeechEvent() {
        return latestSpeechEvent;
    }

    public synchronized List<SpeechEvent> getSpeechEvents() {
        return Collections.unmodifiableList(new ArrayList<>(speechEvents));
    }

    public synchronized SessionSummary getSessionSummary() {
        return latestSessionSummary;
    }

    public synchronized void startSession() {
        speechEvents.clear();
        latestSpeechEvent = null;
        sessionAggregator.reset();
        sessionRunning = true;
        sessionAggregator.setSessionRunning(true);
        latestSessionSummary = sessionAggregator.getSummary();
    }

    public synchronized void stopSession() {
        sessionRunning = false;
        sessionAggregator.setSessionRunning(false);
        latestSessionSummary = sessionAggregator.getSummary();
    }

    public synchronized boolean isSessionRunning() {
        return sessionRunning;
    }

    public void addSpeechStateListener(SpeechStateListener listener) {
        listeners.addIfAbsent(listener);
    }

    public void removeSpeechStateListener(SpeechStateListener listener) {
        listeners.remove(listener);
    }

    private void notifyListeners(SpeechEvent speechEvent) {
        mainHandler.post(() -> {
            for (SpeechStateListener listener : listeners) {
                listener.onSpeechStateChanged(speechEvent);
            }
        });
    }
}
