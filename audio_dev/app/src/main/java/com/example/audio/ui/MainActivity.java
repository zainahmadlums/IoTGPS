package com.example.audio.ui;

import android.Manifest;
import android.content.res.ColorStateList;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.ViewModelProvider;

import com.example.audio.R;
import com.example.audio.data.SessionRepository;
import com.example.audio.data.SpeechEvent;
import com.example.audio.pipeline.SessionSummary;
import com.example.audio.reverb.ReverbResult;
import com.example.audio.service.AudioTrackingService;

public class MainActivity extends AppCompatActivity implements SessionRepository.SpeechStateListener {

    private static final int AUDIO_PERMISSION_REQUEST_CODE = 2001;

    private SessionViewModel sessionViewModel;
    private TextView sessionRunningText;
    private TextView speechStateText;
    private TextView disturbanceStateText;
    private TextView reverbStateText;
    private TextView sessionSummaryText;
    private Button startButton;
    private Button stopButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        sessionViewModel = new ViewModelProvider(this).get(SessionViewModel.class);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (view, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        sessionRunningText = findViewById(R.id.session_running_text);
        speechStateText = findViewById(R.id.speech_state_text);
        disturbanceStateText = findViewById(R.id.disturbance_state_text);
        reverbStateText = findViewById(R.id.reverb_state_text);
        sessionSummaryText = findViewById(R.id.session_summary_text);
        startButton = findViewById(R.id.start_button);
        stopButton = findViewById(R.id.stop_button);

        startButton.setOnClickListener(view -> requestAudioAndStart());
        stopButton.setOnClickListener(view -> stopTracking());
        renderSessionState();
    }

    public SessionViewModel getSessionViewModel() {
        return sessionViewModel;
    }

    @Override
    protected void onResume() {
        super.onResume();
        SessionRepository.getInstance().addSpeechStateListener(this);
        sessionViewModel.setSessionRunning(SessionRepository.getInstance().isSessionRunning());
        SpeechEvent latestSpeechEvent = SessionRepository.getInstance().getLatestSpeechEvent();
        if (latestSpeechEvent != null) {
            sessionViewModel.setSpeechActive(latestSpeechEvent.isSpeech());
            sessionViewModel.setDisturbanceActive(latestSpeechEvent.isDisturbance());
            sessionViewModel.setReverbLevel(latestSpeechEvent.getReverbLevel());
        }
        sessionViewModel.setSessionSummary(SessionRepository.getInstance().getSessionSummary());
        renderSessionState();
    }

    @Override
    protected void onPause() {
        SessionRepository.getInstance().removeSpeechStateListener(this);
        super.onPause();
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != AUDIO_PERMISSION_REQUEST_CODE) {
            return;
        }

        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startTracking();
        } else {
            sessionViewModel.setSessionRunning(false);
            sessionViewModel.setSpeechActive(false);
            sessionViewModel.setDisturbanceActive(false);
            renderSessionState();
        }
    }

    private void requestAudioAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            startTracking();
            return;
        }

        ActivityCompat.requestPermissions(
                this,
                new String[]{Manifest.permission.RECORD_AUDIO},
                AUDIO_PERMISSION_REQUEST_CODE
        );
    }

    private void startTracking() {
        AudioTrackingService.startService(this);
        sessionViewModel.setSessionRunning(true);
        sessionViewModel.setSpeechActive(false);
        sessionViewModel.setDisturbanceActive(false);
        sessionViewModel.setReverbLevel(ReverbResult.Level.LOW);
        sessionViewModel.setSessionSummary(SessionRepository.getInstance().getSessionSummary());
        renderSessionState();
    }

    private void stopTracking() {
        SessionSummary latestSummary = SessionRepository.getInstance().getSessionSummary();
        AudioTrackingService.requestStop(this);
        sessionViewModel.setSessionRunning(false);
        sessionViewModel.setSpeechActive(false);
        sessionViewModel.setDisturbanceActive(false);
        sessionViewModel.setSessionSummary(latestSummary);
        renderSessionState();
    }

    private void renderSessionState() {
        sessionRunningText.setText(getString(
                R.string.session_running_format,
                sessionViewModel.isSessionRunning()
                        ? getString(R.string.session_running)
                        : getString(R.string.session_stopped)
        ));
        speechStateText.setText(getString(
                R.string.current_speech_format,
                toDisplayLabel(sessionViewModel.getSpeechState())
        ));
        disturbanceStateText.setText(getString(
                R.string.current_disturbance_format,
                sessionViewModel.isDisturbanceActive()
                        ? getString(R.string.disturbance_detected)
                        : getString(R.string.disturbance_clear)
        ));
        reverbStateText.setText(getString(
                R.string.current_reverb_format,
                sessionViewModel.getReverbLevel().name()
        ));
        sessionSummaryText.setText(buildSessionSummaryText(sessionViewModel.getSessionSummary()));
        updateButtonState();
    }

    @Override
    public void onSpeechStateChanged(SpeechEvent speechEvent) {
        sessionViewModel.setSessionRunning(SessionRepository.getInstance().isSessionRunning());
        sessionViewModel.setSpeechActive(speechEvent.isSpeech());
        sessionViewModel.setDisturbanceActive(speechEvent.isDisturbance());
        sessionViewModel.setReverbLevel(speechEvent.getReverbLevel());
        sessionViewModel.setSessionSummary(SessionRepository.getInstance().getSessionSummary());
        renderSessionState();
    }

    private String toDisplayLabel(SessionState sessionState) {
        switch (sessionState) {
            case SPEECH:
                return getString(R.string.speech_state_speech);
            case SILENCE:
                return getString(R.string.speech_state_silence);
            case IDLE:
            default:
                return getString(R.string.speech_state_idle);
        }
    }

    private String buildSessionSummaryText(SessionSummary sessionSummary) {
        if (sessionSummary == null) {
            return getString(R.string.session_summary_empty);
        }

        if (sessionViewModel.isSessionRunning()) {
            return getString(
                    R.string.session_summary_live_format,
                    Math.round(sessionSummary.getSpeakingRatio() * 100.0f),
                    sessionSummary.getDisturbanceCount(),
                    sessionSummary.getCoarseReverbLevel().name()
            );
        }

        return getString(
                R.string.session_summary_format,
                Math.round(sessionSummary.getSpeakingRatio() * 100.0f),
                sessionSummary.getDisturbanceCount(),
                sessionSummary.getCoarseReverbLevel().name()
        );
    }

    private void updateButtonState() {
        boolean running = sessionViewModel.isSessionRunning();
        startButton.setEnabled(!running);
        stopButton.setEnabled(running);

        startButton.setBackgroundTintList(ColorStateList.valueOf(
                ContextCompat.getColor(
                        this,
                        running ? R.color.button_disabled_background : R.color.button_start_background
                )
        ));
        stopButton.setBackgroundTintList(ColorStateList.valueOf(
                ContextCompat.getColor(
                        this,
                        running ? R.color.button_stop_background : R.color.button_disabled_background
                )
        ));
        startButton.setTextColor(ContextCompat.getColor(this, R.color.button_text));
        stopButton.setTextColor(ContextCompat.getColor(this, R.color.button_text));
        startButton.setAlpha(running ? 0.65f : 1.0f);
        stopButton.setAlpha(running ? 1.0f : 0.65f);
    }
}
