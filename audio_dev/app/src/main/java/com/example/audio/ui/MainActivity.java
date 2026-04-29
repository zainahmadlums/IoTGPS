package com.example.audio.ui;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.ViewModelProvider;

import com.example.audio.audio.AudioConfig;
import com.example.audio.audio.AudioRecorderManager;
import com.example.audio.data.InstructorVoiceProfile;
import com.example.audio.data.InstructorVoiceProfileStats;
import com.example.audio.data.InstructorVoiceProfileStore;
import com.example.audio.data.SessionAudioFileManager;
import com.example.audio.R;
import com.example.audio.data.SessionRepository;
import com.example.audio.data.SpeechEvent;
import com.example.audio.data.WavSessionRecorder;
import com.example.audio.pipeline.SessionSummary;
import com.example.audio.reverb.ReverbResult;
import com.example.audio.service.AudioTrackingService;
import com.example.audio.ui.dashboard.DashboardFragment;
import com.example.audio.ui.library.AudioLibraryFragment;
import com.google.android.material.bottomnavigation.BottomNavigationView;

import java.io.File;
import java.io.IOException;

public class MainActivity extends AppCompatActivity implements SessionRepository.SpeechStateListener {

    private static final int AUDIO_PERMISSION_REQUEST_CODE = 2001;
    private static final long INSTRUCTOR_ENROLLMENT_DURATION_MILLIS = 30_000L;
    private static final String TAG_DASHBOARD = "dashboard";
    private static final String TAG_LIBRARY = "library";
    private static final int PENDING_ACTION_NONE = 0;
    private static final int PENDING_ACTION_TRACKING = 1;
    private static final int PENDING_ACTION_INSTRUCTOR_SETUP = 2;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable instructorEnrollmentTicker = new Runnable() {
        @Override
        public void run() {
            updateInstructorEnrollmentCountdown();
            if (instructorEnrollmentRunning) {
                mainHandler.postDelayed(this, 250L);
            }
        }
    };
    private SessionViewModel sessionViewModel;
    private BottomNavigationView bottomNavigationView;
    private AudioRecorderManager instructorEnrollmentRecorderManager;
    private WavSessionRecorder instructorEnrollmentWavRecorder;
    private InstructorVoiceProfileStats instructorEnrollmentStats;
    private AudioConfig instructorEnrollmentAudioConfig;
    private File instructorEnrollmentAudioFile;
    private long instructorEnrollmentStartTimeMillis;
    private boolean instructorEnrollmentRunning;
    private int pendingAudioPermissionAction = PENDING_ACTION_NONE;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        sessionViewModel = new ViewModelProvider(this).get(SessionViewModel.class);
        refreshInstructorProfileState();

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
            if (pendingAudioPermissionAction == PENDING_ACTION_INSTRUCTOR_SETUP) {
                startInstructorSetup();
            } else {
                startTracking();
            }
        } else {
            if (pendingAudioPermissionAction == PENDING_ACTION_INSTRUCTOR_SETUP) {
                Toast.makeText(
                        this,
                        R.string.instructor_setup_permission_denied,
                        Toast.LENGTH_SHORT
                ).show();
            }
            sessionViewModel.setSessionRunning(false);
            sessionViewModel.setSpeechActive(false);
            sessionViewModel.setDisturbanceActive(false);
            notifyDashboardStateChanged();
        }
        pendingAudioPermissionAction = PENDING_ACTION_NONE;
    }

    @Override
    public void onSpeechStateChanged(SpeechEvent speechEvent) {
        sessionViewModel.setSessionRunning(SessionRepository.getInstance().isSessionRunning());
        sessionViewModel.setSpeechActive(speechEvent.isSpeech());
        sessionViewModel.setDisturbanceActive(speechEvent.isDisturbance());
        sessionViewModel.setReverbLevel(speechEvent.getReverbLevel());
        sessionViewModel.setSessionSummary(SessionRepository.getInstance().getSessionSummary());
        refreshInstructorProfileState();
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
        if (!InstructorVoiceProfileStore.getInstance().hasProfile(this)) {
            refreshInstructorProfileState();
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            startTracking();
            return;
        }

        pendingAudioPermissionAction = PENDING_ACTION_TRACKING;
        ActivityCompat.requestPermissions(
                this,
                new String[]{Manifest.permission.RECORD_AUDIO},
                AUDIO_PERMISSION_REQUEST_CODE
        );
    }

    public void requestAudioAndStartInstructorSetup() {
        if (instructorEnrollmentRunning) {
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            startInstructorSetup();
            return;
        }

        pendingAudioPermissionAction = PENDING_ACTION_INSTRUCTOR_SETUP;
        ActivityCompat.requestPermissions(
                this,
                new String[]{Manifest.permission.RECORD_AUDIO},
                AUDIO_PERMISSION_REQUEST_CODE
        );
    }

    public void cancelInstructorSetup() {
        if (!instructorEnrollmentRunning) {
            return;
        }
        stopInstructorSetup(false);
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
        if (!InstructorVoiceProfileStore.getInstance().hasProfile(this)) {
            refreshInstructorProfileState();
            return;
        }
        AudioTrackingService.startService(this);
        sessionViewModel.setSessionRunning(true);
        sessionViewModel.setSpeechActive(false);
        sessionViewModel.setDisturbanceActive(false);
        sessionViewModel.setReverbLevel(ReverbResult.Level.LOW);
        sessionViewModel.setSessionSummary(SessionRepository.getInstance().getSessionSummary());
        notifyDashboardStateChanged();
    }

    private void startInstructorSetup() {
        if (instructorEnrollmentRunning) {
            return;
        }

        instructorEnrollmentAudioConfig = AudioConfig.sileroConfig();
        instructorEnrollmentStartTimeMillis = System.currentTimeMillis();
        instructorEnrollmentStats = new InstructorVoiceProfileStats();
        instructorEnrollmentAudioFile = SessionAudioFileManager.createInstructorEnrollmentOutputFile(
                this,
                instructorEnrollmentStartTimeMillis
        );

        try {
            instructorEnrollmentWavRecorder = new WavSessionRecorder(
                    instructorEnrollmentAudioFile,
                    instructorEnrollmentAudioConfig
            );
        } catch (IOException ioException) {
            clearInstructorEnrollmentState();
            Toast.makeText(this, R.string.instructor_setup_failed, Toast.LENGTH_SHORT).show();
            return;
        }

        instructorEnrollmentRecorderManager = new AudioRecorderManager(
                this,
                instructorEnrollmentAudioConfig
        );
        instructorEnrollmentRunning = true;
        sessionViewModel.setInstructorEnrollmentRunning(true);
        sessionViewModel.setInstructorEnrollmentRemainingMillis(INSTRUCTOR_ENROLLMENT_DURATION_MILLIS);
        notifyDashboardStateChanged();

        try {
            instructorEnrollmentRecorderManager.start(new AudioRecorderManager.FrameCallback() {
                @Override
                public void onFrame(short[] frame, long timestampMillis) {
                    persistInstructorEnrollmentFrame(frame);
                }

                @Override
                public void onRecorderFailure(String reason, Throwable throwable) {
                    mainHandler.post(() -> stopInstructorSetup(false));
                }
            });
        } catch (RuntimeException runtimeException) {
            stopInstructorSetup(false);
            Toast.makeText(this, R.string.instructor_setup_failed, Toast.LENGTH_SHORT).show();
            return;
        }

        mainHandler.post(instructorEnrollmentTicker);
        mainHandler.postDelayed(
                () -> stopInstructorSetup(true),
                INSTRUCTOR_ENROLLMENT_DURATION_MILLIS
        );
    }

    private void persistInstructorEnrollmentFrame(short[] frame) {
        if (!instructorEnrollmentRunning
                || instructorEnrollmentWavRecorder == null
                || instructorEnrollmentStats == null
                || frame == null) {
            return;
        }

        try {
            instructorEnrollmentWavRecorder.writeFrame(frame);
            instructorEnrollmentStats.addFrame(frame);
        } catch (IOException ioException) {
            mainHandler.post(() -> stopInstructorSetup(false));
        }
    }

    private void stopInstructorSetup(boolean saveProfile) {
        if (!instructorEnrollmentRunning && instructorEnrollmentWavRecorder == null) {
            return;
        }

        instructorEnrollmentRunning = false;
        mainHandler.removeCallbacks(instructorEnrollmentTicker);
        if (instructorEnrollmentRecorderManager != null) {
            instructorEnrollmentRecorderManager.stop();
            instructorEnrollmentRecorderManager = null;
        }

        long endTimeMillis = System.currentTimeMillis();
        long audioFileSizeBytes = 0L;
        if (instructorEnrollmentWavRecorder != null) {
            try {
                audioFileSizeBytes = instructorEnrollmentWavRecorder.finish();
            } catch (IOException ioException) {
                saveProfile = false;
            }
            instructorEnrollmentWavRecorder = null;
        }

        if (saveProfile && instructorEnrollmentAudioFile != null && instructorEnrollmentStats != null) {
            InstructorVoiceProfile profile = new InstructorVoiceProfile(
                    "instructor-profile-" + instructorEnrollmentStartTimeMillis,
                    "Instructor Voice Setup",
                    InstructorVoiceProfileStore.PROFILE_METADATA_FILE_NAME,
                    instructorEnrollmentAudioFile.getName(),
                    getString(R.string.instructor_setup_prompt),
                    instructorEnrollmentStartTimeMillis,
                    endTimeMillis,
                    Math.max(0L, endTimeMillis - instructorEnrollmentStartTimeMillis),
                    instructorEnrollmentAudioConfig.getSampleRateHz(),
                    instructorEnrollmentAudioConfig.getChannelCount(),
                    instructorEnrollmentAudioConfig.getFrameSizeSamples(),
                    instructorEnrollmentStats.getFrameCount(),
                    audioFileSizeBytes,
                    instructorEnrollmentStats.getAverageRms(),
                    instructorEnrollmentStats.getAverageZcr(),
                    instructorEnrollmentStats.getAverageLowBandRatio(),
                    instructorEnrollmentStats.getAverageHighBandRatio()
            );
            InstructorVoiceProfileStore.getInstance().writeProfile(this, profile);
            Toast.makeText(this, R.string.instructor_setup_saved, Toast.LENGTH_SHORT).show();
        } else if (instructorEnrollmentAudioFile != null && instructorEnrollmentAudioFile.exists()) {
            instructorEnrollmentAudioFile.delete();
        }

        clearInstructorEnrollmentState();
        refreshInstructorProfileState();
        notifyDashboardStateChanged();
    }

    private void clearInstructorEnrollmentState() {
        instructorEnrollmentRunning = false;
        instructorEnrollmentRecorderManager = null;
        instructorEnrollmentWavRecorder = null;
        instructorEnrollmentStats = null;
        instructorEnrollmentAudioConfig = null;
        instructorEnrollmentAudioFile = null;
        instructorEnrollmentStartTimeMillis = 0L;
        sessionViewModel.setInstructorEnrollmentRunning(false);
        sessionViewModel.setInstructorEnrollmentRemainingMillis(0L);
    }

    private void updateInstructorEnrollmentCountdown() {
        long elapsedMillis = Math.max(0L, System.currentTimeMillis() - instructorEnrollmentStartTimeMillis);
        long remainingMillis = Math.max(0L, INSTRUCTOR_ENROLLMENT_DURATION_MILLIS - elapsedMillis);
        sessionViewModel.setInstructorEnrollmentRemainingMillis(remainingMillis);
        notifyDashboardStateChanged();
    }

    private void refreshInstructorProfileState() {
        sessionViewModel.setInstructorProfileReady(
                InstructorVoiceProfileStore.getInstance().hasProfile(this)
        );
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
