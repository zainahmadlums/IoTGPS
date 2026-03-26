package com.example.audio.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.example.audio.R;
import com.example.audio.audio.AudioConfig;
import com.example.audio.audio.AudioRecorderManager;
import com.example.audio.data.SessionArchiveEntry;
import com.example.audio.data.SessionArchiveStore;
import com.example.audio.data.SessionAudioFileManager;
import com.example.audio.data.SessionRepository;
import com.example.audio.data.WavSessionRecorder;
import com.example.audio.disturbance.EnergySpikeDetector;
import com.example.audio.pipeline.FrameAnalysisResult;
import com.example.audio.pipeline.AudioPipelineCoordinator;
import com.example.audio.pipeline.FrameProcessingDiagnostics;
import com.example.audio.pipeline.SessionSummary;
import com.example.audio.reverb.EnergyDecayReverbEstimator;
import com.example.audio.util.Logger;
import com.example.audio.vad.SpeechDetectorFactory;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

public class AudioTrackingService extends Service {

    public static final String ACTION_START = "com.example.audio.action.START_AUDIO_TRACKING";
    public static final String ACTION_STOP = "com.example.audio.action.STOP_AUDIO_TRACKING";

    private static final String CHANNEL_ID = "audio_tracking";
    private static final int NOTIFICATION_ID = 1001;
    private static final String TAG = "AudioTrackingService";

    private AudioRecorderManager audioRecorderManager;
    private AudioPipelineCoordinator audioPipelineCoordinator;
    private SessionRepository sessionRepository;
    private FrameProcessingDiagnostics diagnostics;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean restartInFlight;
    private WavSessionRecorder wavSessionRecorder;
    private File currentSessionAudioFile;
    private int debugFrameBridgeLogs;

    public static Intent createStartIntent(Context context) {
        Intent intent = new Intent(context, AudioTrackingService.class);
        intent.setAction(ACTION_START);
        return intent;
    }

    public static Intent createStopIntent(Context context) {
        Intent intent = new Intent(context, AudioTrackingService.class);
        intent.setAction(ACTION_STOP);
        return intent;
    }

    public static void startService(Context context) {
        ContextCompat.startForegroundService(context, createStartIntent(context));
    }

    public static void requestStop(Context context) {
        context.startService(createStopIntent(context));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        sessionRepository = SessionRepository.getInstance();
        diagnostics = new FrameProcessingDiagnostics();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : ACTION_START;
        if (ACTION_STOP.equals(action)) {
            stopTracking();
            stopSelf();
            return START_NOT_STICKY;
        }

        ensureTrackingComponents();
        startForegroundServiceInternal();
        startTrackingIfNeeded();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        stopTracking();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void ensureTrackingComponents() {
        AudioConfig audioConfig = SpeechDetectorFactory.activeAudioConfig();
        if (audioPipelineCoordinator == null) {
            audioPipelineCoordinator = new AudioPipelineCoordinator(
                    SpeechDetectorFactory.create(this),
                    new EnergySpikeDetector(),
                    new EnergyDecayReverbEstimator()
            );
        }
        if (audioRecorderManager == null) {
            audioRecorderManager = new AudioRecorderManager(this, audioConfig);
        }
    }

    private void startForegroundServiceInternal() {
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            );
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void startTrackingIfNeeded() {
        if (audioRecorderManager.isRecording()) {
            return;
        }

        try {
            sessionRepository.startSession();
            startSessionAudioCapture(audioRecorderManager.getAudioConfig());
            audioRecorderManager.start(new AudioRecorderManager.FrameCallback() {
                @Override
                public void onFrame(short[] frame, long timestampMillis) {
                    if (debugFrameBridgeLogs < 6) {
                        debugFrameBridgeLogs++;
                        Logger.d(
                                TAG,
                                "bridge frame identity="
                                        + System.identityHashCode(frame)
                                        + ", samples="
                                        + frame.length
                                        + ", timestamp="
                                        + timestampMillis
                        );
                    }
                    writeAudioFrame(frame);
                    FrameAnalysisResult analysisResult =
                            audioPipelineCoordinator.process(frame, timestampMillis);
                    diagnostics.record(analysisResult);
                    sessionRepository.append(analysisResult);
                }

                @Override
                public void onRecorderFailure(String reason, Throwable throwable) {
                    Logger.e(TAG, "Recorder failure reported: " + reason, throwable);
                    mainHandler.post(() -> restartTracking("recorder failure: " + reason));
                }
            });
        } catch (RuntimeException runtimeException) {
            abortSessionAudioCapture();
            sessionRepository.stopSession();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            throw runtimeException;
        }
    }

    private void stopTracking() {
        if (audioRecorderManager != null) {
            audioRecorderManager.stop();
        }
        ArchivedAudioArtifact archivedAudioArtifact = finishSessionAudioCapture();
        if (sessionRepository != null && sessionRepository.isSessionRunning()) {
            archiveSessionSummary(archivedAudioArtifact);
        }
        if (sessionRepository != null) {
            sessionRepository.stopSession();
        }
        if (audioPipelineCoordinator != null) {
            audioPipelineCoordinator.close();
            audioPipelineCoordinator = null;
        }
        audioRecorderManager = null;
        stopForeground(STOP_FOREGROUND_REMOVE);
    }

    private void archiveSessionSummary(ArchivedAudioArtifact archivedAudioArtifact) {
        long startTimeMillis = sessionRepository.getSessionStartTimeMillis();
        long endTimeMillis = Math.max(startTimeMillis, sessionRepository.getSessionEndTimeMillis());
        if (startTimeMillis <= 0L || endTimeMillis <= 0L) {
            return;
        }

        long durationMillis = Math.max(1_000L, endTimeMillis - startTimeMillis);
        SessionSummary sessionSummary = sessionRepository.getSessionSummary();
        String sessionId = "session-" + endTimeMillis;
        String generatedFilename = archivedAudioArtifact != null
                ? archivedAudioArtifact.generatedFilename
                : String.format(
                        Locale.US,
                        "deployteach_%1$tY%1$tm%1$td_%1$tH%1$tM%1$tS.wav",
                        startTimeMillis
                );
        String title = String.format(
                Locale.US,
                "Session %1$tb %1$td • %1$tI:%1$tM %1$Tp",
                startTimeMillis
        );

        SessionArchiveStore.getInstance().archiveSession(
                this,
                new SessionArchiveEntry(
                        sessionId,
                        title,
                        generatedFilename,
                        startTimeMillis,
                        endTimeMillis,
                        durationMillis,
                        archivedAudioArtifact != null
                                ? archivedAudioArtifact.fileSizeBytes
                                : estimateCaptureFootprintBytes(durationMillis),
                        sessionSummary != null ? sessionSummary.getSpeakingRatio() : 0.0f,
                        sessionSummary != null ? sessionSummary.getDisturbanceCount() : 0,
                        sessionSummary != null
                                ? sessionSummary.getCoarseReverbLevel()
                                : com.example.audio.reverb.ReverbResult.Level.LOW
                )
        );
    }

    private long estimateCaptureFootprintBytes(long durationMillis) {
        long seconds = Math.max(1L, durationMillis / 1000L);
        return seconds * 96_000L;
    }

    private void startSessionAudioCapture(AudioConfig audioConfig) {
        long startTimeMillis = sessionRepository.getSessionStartTimeMillis();
        currentSessionAudioFile = SessionAudioFileManager.createOutputFile(this, startTimeMillis);
        try {
            wavSessionRecorder = new WavSessionRecorder(currentSessionAudioFile, audioConfig);
        } catch (IOException ioException) {
            currentSessionAudioFile = null;
            throw new IllegalStateException("Failed to create archived audio file.", ioException);
        }
    }

    private void writeAudioFrame(short[] frame) {
        if (wavSessionRecorder == null) {
            return;
        }

        try {
            wavSessionRecorder.writeFrame(frame);
        } catch (IOException ioException) {
            Logger.e(TAG, "Failed to write session audio frame.", ioException);
            abortSessionAudioCapture();
        }
    }

    private ArchivedAudioArtifact finishSessionAudioCapture() {
        if (wavSessionRecorder == null || currentSessionAudioFile == null) {
            return null;
        }

        try {
            long fileSizeBytes = wavSessionRecorder.finish();
            ArchivedAudioArtifact archivedAudioArtifact = new ArchivedAudioArtifact(
                    currentSessionAudioFile.getName(),
                    fileSizeBytes
            );
            wavSessionRecorder = null;
            currentSessionAudioFile = null;
            return archivedAudioArtifact;
        } catch (IOException ioException) {
            Logger.e(TAG, "Failed to finalize archived audio file.", ioException);
            abortSessionAudioCapture();
            return null;
        }
    }

    private void abortSessionAudioCapture() {
        if (wavSessionRecorder != null) {
            wavSessionRecorder.abort();
        }
        wavSessionRecorder = null;
        currentSessionAudioFile = null;
    }

    private void restartTracking(String reason) {
        if (restartInFlight) {
            return;
        }

        restartInFlight = true;
        Logger.e(TAG, "Restarting audio tracking due to " + reason + ".");
        try {
            stopTracking();
            ensureTrackingComponents();
            startTrackingIfNeeded();
        } finally {
            restartInFlight = false;
        }
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        stopTracking();
        stopSelf();
        super.onTaskRemoved(rootIntent);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }

        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.audio_tracking_channel_name),
                NotificationManager.IMPORTANCE_LOW
        );
        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        if (notificationManager != null) {
            notificationManager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(getString(R.string.audio_tracking_notification_title))
                .setContentText(getString(R.string.audio_tracking_notification_text))
                .setOngoing(true)
                .build();
    }

    private static final class ArchivedAudioArtifact {
        private final String generatedFilename;
        private final long fileSizeBytes;

        private ArchivedAudioArtifact(String generatedFilename, long fileSizeBytes) {
            this.generatedFilename = generatedFilename;
            this.fileSizeBytes = fileSizeBytes;
        }
    }
}
