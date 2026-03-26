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
import com.example.audio.reverb.ReverbResult;
import com.example.audio.ui.MainActivity;
import com.example.audio.ui.SessionState;
import com.example.audio.ui.SessionViewModel;
import com.example.audio.ui.library.AudioLibraryRepository;
import com.example.audio.ui.library.AudioSessionFormatter;
import com.example.audio.ui.library.AudioSessionItem;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import java.util.List;
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
    private TextView recentPreviewTitle;
    private TextView recentPreviewMeta;
    private TextView recentPreviewBody;
    private MaterialCardView heroCard;
    private MaterialCardView recentPreviewCard;
    private MaterialButton startButton;
    private MaterialButton stopButton;
    private MaterialButton libraryButton;

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
        recentPreviewCard = view.findViewById(R.id.recent_preview_card);
        heroLabelText = view.findViewById(R.id.hero_status_text);
        sessionStatusValue = view.findViewById(R.id.session_status_value);
        speechStatusValue = view.findViewById(R.id.speech_status_value);
        disturbanceStatusValue = view.findViewById(R.id.disturbance_status_value);
        reverbStatusValue = view.findViewById(R.id.reverb_status_value);
        summaryValueText = view.findViewById(R.id.summary_value_text);
        speechMetricValue = view.findViewById(R.id.speech_metric_value);
        disturbanceMetricValue = view.findViewById(R.id.disturbance_metric_value);
        reverbMetricValue = view.findViewById(R.id.reverb_metric_value);
        recentPreviewTitle = view.findViewById(R.id.recent_preview_title);
        recentPreviewMeta = view.findViewById(R.id.recent_preview_meta);
        recentPreviewBody = view.findViewById(R.id.recent_preview_body);
        startButton = view.findViewById(R.id.start_tracking_button);
        stopButton = view.findViewById(R.id.stop_tracking_button);
        libraryButton = view.findViewById(R.id.view_library_button);

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
        libraryButton.setOnClickListener(v -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).navigateToLibrary();
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
        SessionSummary sessionSummary = sessionViewModel.getSessionSummary();
        int speechPercent = sessionSummary == null
                ? 0
                : Math.round(sessionSummary.getSpeakingRatio() * 100.0f);
        int disturbanceCount = sessionSummary == null ? 0 : sessionSummary.getDisturbanceCount();
        String reverbLabel = sessionViewModel.getReverbLevel().name().toLowerCase(Locale.US);

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

        startButton.setEnabled(!running);
        stopButton.setEnabled(running);
        startButton.setAlpha(running ? 0.55f : 1.0f);
        stopButton.setAlpha(running ? 1.0f : 0.7f);

        List<AudioSessionItem> libraryItems = AudioLibraryRepository.getInstance()
                .getSessions(requireContext());
        if (libraryItems.isEmpty()) {
            recentPreviewTitle.setText(R.string.dashboard_preview_unavailable);
            recentPreviewMeta.setText(R.string.dashboard_recent_title);
            recentPreviewBody.setText(R.string.dashboard_recent_empty);
            recentPreviewCard.setAlpha(0.9f);
            return;
        }

        AudioSessionItem item = libraryItems.get(0);
        recentPreviewTitle.setText(item.getTitle());
        recentPreviewMeta.setText(
                AudioSessionFormatter.formatDateTime(item.getStartTimeMillis())
                        + " • "
                        + AudioSessionFormatter.formatDuration(item.getDurationMillis())
        );
        recentPreviewBody.setText(getString(
                R.string.session_summary_live_format,
                Math.round(item.getSpeechRatio() * 100.0f),
                item.getDisturbanceCount(),
                item.getReverbLevel().name()
        ));
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
