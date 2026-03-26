package com.example.audio.ui;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;

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
import com.example.audio.ui.dashboard.DashboardFragment;
import com.example.audio.ui.library.AudioLibraryFragment;
import com.google.android.material.bottomnavigation.BottomNavigationView;

public class MainActivity extends AppCompatActivity implements SessionRepository.SpeechStateListener {

    private static final int AUDIO_PERMISSION_REQUEST_CODE = 2001;
    private static final String TAG_DASHBOARD = "dashboard";
    private static final String TAG_LIBRARY = "library";

    private SessionViewModel sessionViewModel;
    private BottomNavigationView bottomNavigationView;

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

        bottomNavigationView = findViewById(R.id.bottom_navigation);
        bottomNavigationView.setOnItemSelectedListener(item -> {
            if (item.getItemId() == R.id.navigation_library) {
                showLibraryScreen();
                return true;
            }

            showDashboardScreen();
            return true;
        });

        if (savedInstanceState == null) {
            bottomNavigationView.setSelectedItemId(R.id.navigation_dashboard);
            showDashboardScreen();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        SessionRepository.getInstance().addSpeechStateListener(this);
        refreshSessionStateFromRepository();
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
            notifyDashboardStateChanged();
        }
    }

    @Override
    public void onSpeechStateChanged(SpeechEvent speechEvent) {
        sessionViewModel.setSessionRunning(SessionRepository.getInstance().isSessionRunning());
        sessionViewModel.setSpeechActive(speechEvent.isSpeech());
        sessionViewModel.setDisturbanceActive(speechEvent.isDisturbance());
        sessionViewModel.setReverbLevel(speechEvent.getReverbLevel());
        sessionViewModel.setSessionSummary(SessionRepository.getInstance().getSessionSummary());
        notifyDashboardStateChanged();
    }

    public SessionViewModel getSessionViewModel() {
        return sessionViewModel;
    }

    public void refreshSessionStateFromRepository() {
        sessionViewModel.setSessionRunning(SessionRepository.getInstance().isSessionRunning());
        SpeechEvent latestSpeechEvent = SessionRepository.getInstance().getLatestSpeechEvent();
        if (latestSpeechEvent != null) {
            sessionViewModel.setSpeechActive(latestSpeechEvent.isSpeech());
            sessionViewModel.setDisturbanceActive(latestSpeechEvent.isDisturbance());
            sessionViewModel.setReverbLevel(latestSpeechEvent.getReverbLevel());
        }
        sessionViewModel.setSessionSummary(SessionRepository.getInstance().getSessionSummary());
        notifyDashboardStateChanged();
    }

    public void requestAudioAndStartTracking() {
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

    public void stopTracking() {
        SessionSummary latestSummary = SessionRepository.getInstance().getSessionSummary();
        AudioTrackingService.requestStop(this);
        sessionViewModel.setSessionRunning(false);
        sessionViewModel.setSpeechActive(false);
        sessionViewModel.setDisturbanceActive(false);
        sessionViewModel.setSessionSummary(latestSummary);
        notifyDashboardStateChanged();
    }

    public void navigateToLibrary() {
        bottomNavigationView.setSelectedItemId(R.id.navigation_library);
    }

    private void startTracking() {
        AudioTrackingService.startService(this);
        sessionViewModel.setSessionRunning(true);
        sessionViewModel.setSpeechActive(false);
        sessionViewModel.setDisturbanceActive(false);
        sessionViewModel.setReverbLevel(ReverbResult.Level.LOW);
        sessionViewModel.setSessionSummary(SessionRepository.getInstance().getSessionSummary());
        notifyDashboardStateChanged();
    }

    private void showDashboardScreen() {
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.main_nav_host, new DashboardFragment(), TAG_DASHBOARD)
                .commit();
    }

    private void showLibraryScreen() {
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.main_nav_host, new AudioLibraryFragment(), TAG_LIBRARY)
                .commit();
    }

    private void notifyDashboardStateChanged() {
        DashboardFragment dashboardFragment = (DashboardFragment)
                getSupportFragmentManager().findFragmentByTag(TAG_DASHBOARD);
        if (dashboardFragment != null) {
            dashboardFragment.renderSessionState();
        }
    }
}
