package com.example.audio.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.example.audio.R;
import com.example.audio.audio.AudioConfig;
import com.example.audio.audio.AudioRecorderManager;
import com.example.audio.data.SessionRepository;
import com.example.audio.disturbance.EnergySpikeDetector;
import com.example.audio.pipeline.FrameAnalysisResult;
import com.example.audio.pipeline.AudioPipelineCoordinator;
import com.example.audio.pipeline.FrameProcessingDiagnostics;
import com.example.audio.reverb.EnergyDecayReverbEstimator;
import com.example.audio.vad.WebRtcSpeechDetector;

public class AudioTrackingService extends Service {

    public static final String ACTION_START = "com.example.audio.action.START_AUDIO_TRACKING";
    public static final String ACTION_STOP = "com.example.audio.action.STOP_AUDIO_TRACKING";

    private static final String CHANNEL_ID = "audio_tracking";
    private static final int NOTIFICATION_ID = 1001;

    private AudioRecorderManager audioRecorderManager;
    private AudioPipelineCoordinator audioPipelineCoordinator;
    private SessionRepository sessionRepository;
    private FrameProcessingDiagnostics diagnostics;

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
        AudioConfig audioConfig = AudioConfig.defaultConfig();
        if (!WebRtcSpeechDetector.supports(audioConfig)) {
            throw new IllegalStateException("WebRTC VAD configuration must use 16 kHz mono 20 ms frames.");
        }
        if (audioPipelineCoordinator == null) {
            audioPipelineCoordinator = new AudioPipelineCoordinator(
                    new WebRtcSpeechDetector(),
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
            audioRecorderManager.start((frame, timestampMillis) -> {
                FrameAnalysisResult analysisResult =
                        audioPipelineCoordinator.process(frame, timestampMillis);
                diagnostics.record(analysisResult);
                sessionRepository.append(analysisResult);
            });
        } catch (RuntimeException runtimeException) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            throw runtimeException;
        }
    }

    private void stopTracking() {
        if (audioRecorderManager != null) {
            audioRecorderManager.stop();
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
}
