package com.example.audio.ui.detail;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.audio.R;
import com.example.audio.ui.library.AudioSessionFormatter;
import com.example.audio.ui.library.AudioSessionItem;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.snackbar.Snackbar;

public class AudioDetailActivity extends AppCompatActivity {

    private static final String EXTRA_SESSION_ITEM = "extra_session_item";
    private static final long PREVIEW_TICK_MILLIS = 250L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private AudioSessionItem sessionItem;
    private MaterialButton playPauseButton;
    private LinearProgressIndicator progressIndicator;
    private TextView currentTimeText;
    private TextView totalTimeText;
    private TextView unavailableBodyText;
    private boolean previewPlaying;
    private long previewPositionMillis;
    private final Runnable previewTicker = new Runnable() {
        @Override
        public void run() {
            if (!previewPlaying || sessionItem == null) {
                return;
            }

            previewPositionMillis = Math.min(
                    sessionItem.getDurationMillis(),
                    previewPositionMillis + PREVIEW_TICK_MILLIS
            );
            renderPlayback();
            if (previewPositionMillis >= sessionItem.getDurationMillis()) {
                previewPlaying = false;
                playPauseButton.setText(R.string.detail_play);
                return;
            }

            handler.postDelayed(this, PREVIEW_TICK_MILLIS);
        }
    };

    public static Intent createIntent(Context context, AudioSessionItem item) {
        Intent intent = new Intent(context, AudioDetailActivity.class);
        intent.putExtra(EXTRA_SESSION_ITEM, item);
        return intent;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_audio_detail);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.detail_root), (view, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        sessionItem = (AudioSessionItem) getIntent().getSerializableExtra(EXTRA_SESSION_ITEM);
        MaterialToolbar toolbar = findViewById(R.id.detail_toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        TextView titleText = findViewById(R.id.detail_title);
        TextView badgeText = findViewById(R.id.detail_badge);
        TextView timestampText = findViewById(R.id.detail_timestamp_value);
        TextView durationText = findViewById(R.id.detail_duration_value);
        TextView fileSizeText = findViewById(R.id.detail_file_size_value);
        TextView speechRatioText = findViewById(R.id.detail_speech_ratio_value);
        TextView disturbanceText = findViewById(R.id.detail_disturbance_value);
        TextView reverbText = findViewById(R.id.detail_reverb_value);
        currentTimeText = findViewById(R.id.detail_current_time);
        totalTimeText = findViewById(R.id.detail_total_time);
        unavailableBodyText = findViewById(R.id.detail_unavailable_body);
        playPauseButton = findViewById(R.id.detail_play_pause_button);
        MaterialButton renameButton = findViewById(R.id.detail_rename_button);
        MaterialButton deleteButton = findViewById(R.id.detail_delete_button);
        MaterialButton shareButton = findViewById(R.id.detail_share_button);
        progressIndicator = findViewById(R.id.detail_progress);

        if (sessionItem == null) {
            titleText.setText(R.string.detail_title_fallback);
            badgeText.setText(R.string.detail_session_badge);
            timestampText.setText(R.string.detail_unknown_time);
            durationText.setText(R.string.detail_unknown_time);
            fileSizeText.setText(R.string.detail_unknown_time);
            speechRatioText.setText("0%");
            disturbanceText.setText("0");
            reverbText.setText("LOW");
            unavailableBodyText.setText(R.string.detail_error_body);
            playPauseButton.setEnabled(false);
            return;
        }

        titleText.setText(sessionItem.getTitle());
        badgeText.setText(sessionItem.isSampleData()
                ? R.string.detail_session_badge
                : R.string.library_title);
        timestampText.setText(
                AudioSessionFormatter.formatDateTime(sessionItem.getStartTimeMillis())
                        + "\n"
                        + AudioSessionFormatter.formatTimeRange(
                                sessionItem.getStartTimeMillis(),
                                sessionItem.getEndTimeMillis()
                        )
        );
        durationText.setText(AudioSessionFormatter.formatDuration(sessionItem.getDurationMillis()));
        fileSizeText.setText(AudioSessionFormatter.formatFileSize(sessionItem.getFileSizeBytes()));
        speechRatioText.setText(Math.round(sessionItem.getSpeechRatio() * 100.0f) + "%");
        disturbanceText.setText(String.valueOf(sessionItem.getDisturbanceCount()));
        reverbText.setText(sessionItem.getReverbLevel().name());

        progressIndicator.setMax((int) Math.max(1L, sessionItem.getDurationMillis()));
        totalTimeText.setText(AudioSessionFormatter.formatClockDuration(sessionItem.getDurationMillis()));
        renderPlayback();

        playPauseButton.setEnabled(sessionItem.isPlaybackAvailable());
        unavailableBodyText.setText(sessionItem.isPlaybackAvailable()
                ? R.string.detail_loading_body
                : R.string.detail_playback_unavailable);
        playPauseButton.setOnClickListener(v -> togglePlayback());
        renameButton.setOnClickListener(v -> Toast.makeText(
                this,
                getString(R.string.detail_rename_placeholder),
                Toast.LENGTH_SHORT
        ).show());
        deleteButton.setOnClickListener(v -> Snackbar.make(
                v,
                R.string.detail_delete_placeholder,
                Snackbar.LENGTH_SHORT
        ).show());
        shareButton.setOnClickListener(v -> Snackbar.make(
                v,
                R.string.detail_share_placeholder,
                Snackbar.LENGTH_SHORT
        ).show());
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(previewTicker);
        super.onDestroy();
    }

    private void togglePlayback() {
        if (sessionItem == null || !sessionItem.isPlaybackAvailable()) {
            return;
        }

        previewPlaying = !previewPlaying;
        playPauseButton.setText(previewPlaying ? R.string.detail_pause : R.string.detail_play);
        if (previewPlaying) {
            if (previewPositionMillis >= sessionItem.getDurationMillis()) {
                previewPositionMillis = 0L;
            }
            handler.post(previewTicker);
        } else {
            handler.removeCallbacks(previewTicker);
        }
    }

    private void renderPlayback() {
        progressIndicator.setProgress((int) previewPositionMillis);
        currentTimeText.setText(AudioSessionFormatter.formatClockDuration(previewPositionMillis));
    }
}
