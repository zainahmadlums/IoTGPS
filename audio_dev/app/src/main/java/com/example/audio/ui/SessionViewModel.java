package com.example.audio.ui;

import androidx.lifecycle.ViewModel;

import com.example.audio.data.SpeakerRole;
import com.example.audio.pipeline.SessionSummary;
import com.example.audio.reverb.ReverbResult;

public class SessionViewModel extends ViewModel {

    private boolean sessionRunning;
    private SessionState speechState = SessionState.IDLE;
    private SpeakerRole tentativeSpeakerRole = SpeakerRole.SILENCE;
    private boolean disturbanceActive;
    private ReverbResult.Level reverbLevel = ReverbResult.Level.LOW;
    private SessionSummary sessionSummary;
    private boolean instructorProfileReady;
    private boolean instructorEnrollmentRunning;
    private long instructorEnrollmentRemainingMillis;

    public boolean isSessionRunning() {
        return sessionRunning;
    }

    public void setSessionRunning(boolean sessionRunning) {
        this.sessionRunning = sessionRunning;
    }

    public void setSpeechActive(boolean speechActive) {
        this.speechState = speechActive ? SessionState.SPEECH : SessionState.SILENCE;
    }

    public void setSpeakerRole(SpeakerRole speakerRole) {
        tentativeSpeakerRole = speakerRole == null ? SpeakerRole.SILENCE : speakerRole;
    }

    public SessionState getSpeechState() {
        return speechState;
    }

    public SpeakerRole getTentativeSpeakerRole() {
        return tentativeSpeakerRole;
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

    public boolean isInstructorProfileReady() {
        return instructorProfileReady;
    }

    public void setInstructorProfileReady(boolean instructorProfileReady) {
        this.instructorProfileReady = instructorProfileReady;
    }

    public boolean isInstructorEnrollmentRunning() {
        return instructorEnrollmentRunning;
    }

    public void setInstructorEnrollmentRunning(boolean instructorEnrollmentRunning) {
        this.instructorEnrollmentRunning = instructorEnrollmentRunning;
    }

    public long getInstructorEnrollmentRemainingMillis() {
        return instructorEnrollmentRemainingMillis;
    }

    public void setInstructorEnrollmentRemainingMillis(long instructorEnrollmentRemainingMillis) {
        this.instructorEnrollmentRemainingMillis = instructorEnrollmentRemainingMillis;
    }
}
