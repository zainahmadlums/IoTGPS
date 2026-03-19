package com.example.audio.ui;

import androidx.lifecycle.ViewModel;

import com.example.audio.pipeline.SessionSummary;
import com.example.audio.reverb.ReverbResult;

public class SessionViewModel extends ViewModel {

    private boolean sessionRunning;
    private SessionState speechState = SessionState.IDLE;
    private boolean disturbanceActive;
    private ReverbResult.Level reverbLevel = ReverbResult.Level.LOW;
    private SessionSummary sessionSummary;

    public boolean isSessionRunning() {
        return sessionRunning;
    }

    public void setSessionRunning(boolean sessionRunning) {
        this.sessionRunning = sessionRunning;
    }

    public void setSpeechActive(boolean speechActive) {
        this.speechState = speechActive ? SessionState.SPEECH : SessionState.SILENCE;
    }

    public SessionState getSpeechState() {
        return speechState;
    }

    public boolean isDisturbanceActive() {
        return disturbanceActive;
    }

    public void setDisturbanceActive(boolean disturbanceActive) {
        this.disturbanceActive = disturbanceActive;
    }

    public ReverbResult.Level getReverbLevel() {
        return reverbLevel;
    }

    public void setReverbLevel(ReverbResult.Level reverbLevel) {
        this.reverbLevel = reverbLevel;
    }

    public SessionSummary getSessionSummary() {
        return sessionSummary;
    }

    public void setSessionSummary(SessionSummary sessionSummary) {
        this.sessionSummary = sessionSummary;
    }
}
