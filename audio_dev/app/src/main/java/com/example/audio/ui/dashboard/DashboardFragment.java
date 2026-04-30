package com.example.audio.ui.dashboard;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.example.audio.R;
import com.example.audio.pipeline.SessionSummary;
import com.example.audio.ui.MainActivity;
import com.example.audio.ui.SessionState;
import com.example.audio.ui.SessionViewModel;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import java.util.Locale;

public class DashboardFragment extends Fragment {

    private SessionViewModel sessionViewModel;
    private TextView heroLabelText;
    private TextView sessionStatusValue;
    private TextView speechStatusValue;
    private TextView disturbanceStatusValue;
    private TextView reverbStatusValue;
    private TextView summaryValueText;
    private TextView speechMetricValue;
    private TextView disturbanceMetricValue;
    private TextView reverbMetricValue;
    private TextView instructorSetupStatusText;
    private TextView instructorSetupTimerText;
    private MaterialCardView heroCard;
    private MaterialCardView instructorSetupCard;
    private View dashboardMetricsRow;
    private MaterialButton startButton;
    private MaterialButton stopButton;
    private MaterialButton startInstructorSetupButton;
    private MaterialButton cancelInstructorSetupButton;

    public DashboardFragment() {
        super(R.layout.fragment_dashboard);
    }

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState
    ) {
        return inflater.inflate(R.layout.fragment_dashboard, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        sessionViewModel = new ViewModelProvider(requireActivity()).get(SessionViewModel.class);

        heroCard = view.findViewById(R.id.dashboard_hero_card);
        instructorSetupCard = view.findViewById(R.id.instructor_setup_card);
        dashboardMetricsRow = view.findViewById(R.id.dashboard_metrics_row);
        heroLabelText = view.findViewById(R.id.hero_status_text);
        sessionStatusValue = view.findViewById(R.id.session_status_value);
        speechStatusValue = view.findViewById(R.id.speech_status_value);
        disturbanceStatusValue = view.findViewById(R.id.disturbance_status_value);
        reverbStatusValue = view.findViewById(R.id.reverb_status_value);
        summaryValueText = view.findViewById(R.id.summary_value_text);
        speechMetricValue = view.findViewById(R.id.speech_metric_value);
        disturbanceMetricValue = view.findViewById(R.id.disturbance_metric_value);
        reverbMetricValue = view.findViewById(R.id.reverb_metric_value);
        instructorSetupStatusText = view.findViewById(R.id.instructor_setup_status_text);
        instructorSetupTimerText = view.findViewById(R.id.instructor_setup_timer_text);
        startButton = view.findViewById(R.id.start_tracking_button);
        stopButton = view.findViewById(R.id.stop_tracking_button);
        startInstructorSetupButton = view.findViewById(R.id.start_instructor_setup_button);
        cancelInstructorSetupButton = view.findViewById(R.id.cancel_instructor_setup_button);

        startButton.setOnClickListener(v -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).requestAudioAndStartTracking();
            }
        });
        stopButton.setOnClickListener(v -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).stopTracking();
            }
        });
        startInstructorSetupButton.setOnClickListener(v -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).requestAudioAndStartInstructorSetup();
            }
        });
        cancelInstructorSetupButton.setOnClickListener(v -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).cancelInstructorSetup();
            }
        });

        renderSessionState();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).refreshSessionStateFromRepository();
        } else {
            renderSessionState();
        }
    }

    public void renderSessionState() {
        if (getView() == null || sessionViewModel == null || getContext() == null) {
            return;
        }

        boolean running = sessionViewModel.isSessionRunning();
        boolean instructorProfileReady = sessionViewModel.isInstructorProfileReady();
        boolean enrollmentRunning = sessionViewModel.isInstructorEnrollmentRunning();
        SessionSummary sessionSummary = sessionViewModel.getSessionSummary();
        int speechPercent = sessionSummary == null
                ? 0
                : Math.round(sessionSummary.getSpeakingRatio() * 100.0f);
        int disturbanceCount = sessionSummary == null ? 0 : sessionSummary.getDisturbanceCount();
        String reverbLabel = sessionViewModel.getReverbLevel().name().toLowerCase(Locale.US);

        instructorSetupCard.setVisibility(instructorProfileReady ? View.GONE : View.VISIBLE);
        heroCard.setVisibility(instructorProfileReady ? View.VISIBLE : View.GONE);
        dashboardMetricsRow.setVisibility(instructorProfileReady ? View.VISIBLE : View.GONE);
        if (!instructorProfileReady) {
            instructorSetupStatusText.setText(enrollmentRunning
                    ? R.string.instructor_setup_recording
                    : R.string.instructor_setup_required);
            long remainingSeconds = Math.max(
                    0L,
                    (sessionViewModel.getInstructorEnrollmentRemainingMillis() + 999L) / 1000L
            );
            instructorSetupTimerText.setText(enrollmentRunning
                    ? getString(R.string.instructor_setup_timer_format, remainingSeconds)
                    : getString(R.string.instructor_setup_timer_ready));
            startInstructorSetupButton.setEnabled(!enrollmentRunning);
            startInstructorSetupButton.setAlpha(enrollmentRunning ? 0.55f : 1.0f);
            cancelInstructorSetupButton.setEnabled(enrollmentRunning);
            cancelInstructorSetupButton.setAlpha(enrollmentRunning ? 1.0f : 0.55f);
        }

        heroLabelText.setText(running
                ? R.string.dashboard_status_running
                : R.string.dashboard_status_stopped);
        sessionStatusValue.setText(running
                ? R.string.session_running
                : R.string.session_stopped);
        speechStatusValue.setText(toDisplayLabel(sessionViewModel.getSpeechState()));
        disturbanceStatusValue.setText(sessionViewModel.isDisturbanceActive()
                ? getString(R.string.disturbance_detected)
                : getString(R.string.disturbance_clear));
        reverbStatusValue.setText(sessionViewModel.getReverbLevel().name());
        summaryValueText.setText(buildSummaryText(sessionSummary, running));
        speechMetricValue.setText(getString(R.string.dashboard_metric_speech_value, speechPercent));
        disturbanceMetricValue.setText(
                getString(R.string.dashboard_metric_disturbance_value, disturbanceCount)
        );
        reverbMetricValue.setText(
                getString(R.string.dashboard_metric_reverb_value, capitalize(reverbLabel))
        );

        startButton.setEnabled(!running && instructorProfileReady);
        stopButton.setEnabled(running);
        startButton.setAlpha(running || !instructorProfileReady ? 0.55f : 1.0f);
        stopButton.setAlpha(running ? 1.0f : 0.7f);
    }

    private String buildSummaryText(SessionSummary sessionSummary, boolean running) {
        if (sessionSummary == null) {
            return getString(R.string.session_summary_empty);
        }

        int speechPercent = Math.round(sessionSummary.getSpeakingRatio() * 100.0f);
        if (running) {
            return getString(
                    R.string.session_summary_live_format,
                    speechPercent,
                    sessionSummary.getDisturbanceCount(),
                    sessionSummary.getCoarseReverbLevel().name()
            );
        }

        return getString(
                R.string.session_summary_format,
                speechPercent,
                sessionSummary.getDisturbanceCount(),
                sessionSummary.getCoarseReverbLevel().name()
        );
    }

    private String toDisplayLabel(SessionState sessionState) {
        switch (sessionState) {
            case INSTRUCTOR:
                return getString(R.string.speech_state_instructor);
            case STUDENT:
                return getString(R.string.speech_state_student);
            case BOTH:
                return getString(R.string.speech_state_both);
            case SPEECH:
                return getString(R.string.speech_state_speech);
            case SILENCE:
                return getString(R.string.speech_state_silence);
            case STARTING:
                return getString(R.string.session_running);
            case STOPPED:
                return getString(R.string.session_stopped);
            case IDLE:
            case RUNNING:
            default:
                return getString(R.string.speech_state_idle);
        }
    }

    private String capitalize(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }
}
