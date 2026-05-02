package com.example.audio.ui.detail;

import android.content.Context;
import android.content.Intent;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.audio.R;
import com.example.audio.data.SessionAudioFileManager;
import com.example.audio.data.SessionMetadata;
import com.example.audio.ui.library.AudioLibraryRepository;
import com.example.audio.ui.library.AudioSessionFormatter;
import com.example.audio.ui.library.AudioSessionItem;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;esc
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.slider.Slider;
import com.google.android.material.snackbar.Snackbar;

import java.io.File;

public class AudioDetailActivity extends AppCompatActivity {

    private static final String EXTRA_SESSION_ITEM = "extra_session_item";
    private static final long PLAYBACK_PROGRESS_UPDATE_INTERVAL_MS = 200L;

    private AudioSessionItem sessionItem;
    private SessionMetadata sessionMetadata;
    private MediaPlayer mediaPlayer;
    private String activePlaybackFileName;
    private String selectedPlaybackFileName;
    private int selectedPlaybackLabelRes = R.string.detail_playback_source_idle;
    private int selectedPlaybackPositionMs;
    private boolean updatingPlaybackSlider;
    private boolean userSeekingPlayback;
    private final Handler playbackHandler = new Handler(Looper.getMainLooper());
    private final Runnable playbackProgressRunnable = new Runnable() {
        @Override
        public void run() {
            syncPlaybackProgress(false);
            if (mediaPlayer != null) {
                playbackHandler.postDelayed(this, PLAYBACK_PROGRESS_UPDATE_INTERVAL_MS);
            }
        }
    };

    private MaterialButton playRawButton;
    private MaterialButton playFilteredButton;
    private MaterialButton shareRawButton;
    private MaterialButton shareFilteredButton;
    private MaterialButton renameButton;
    private MaterialButton deleteButton;
    private MaterialButton shareMetadataButton;
    private TextView playbackSourceLabel;
    private TextView playbackCurrentTimeText;
    private TextView playbackTotalTimeText;
    private TextView actionHintText;
    private Slider playbackSlider;

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
        if (sessionItem != null) {
            sessionMetadata = AudioLibraryRepository.getInstance().getSessionMetadata(this, sessionItem);
        }

        playRawButton = findViewById(R.id.detail_play_raw_button);
        playFilteredButton = findViewById(R.id.detail_play_filtered_button);
        shareRawButton = findViewById(R.id.detail_share_raw_button);
        shareFilteredButton = findViewById(R.id.detail_share_filtered_button);
        renameButton = findViewById(R.id.detail_rename_button);
        deleteButton = findViewById(R.id.detail_delete_button);
        shareMetadataButton = findViewById(R.id.detail_share_button);
        playbackSourceLabel = findViewById(R.id.detail_playback_source_label);
        playbackCurrentTimeText = findViewById(R.id.detail_playback_current_time);
        playbackTotalTimeText = findViewById(R.id.detail_playback_total_time);
        actionHintText = findViewById(R.id.detail_action_hint);
        playbackSlider = findViewById(R.id.detail_playback_slider);

        playRawButton.setOnClickListener(v -> togglePlayback(resolveRawAudioFile(), R.string.detail_playback_source_raw));
        playFilteredButton.setOnClickListener(v -> togglePlayback(resolveConditionedAudioFile(), R.string.detail_playback_source_filtered));
        shareRawButton.setOnClickListener(v -> shareAudioFile(resolveRawAudioFile(), R.string.detail_share_audio_chooser_raw));
        shareFilteredButton.setOnClickListener(v -> shareAudioFile(resolveConditionedAudioFile(), R.string.detail_share_audio_chooser_filtered));
        renameButton.setOnClickListener(v -> showRenameDialog());
        deleteButton.setOnClickListener(v -> showDeleteDialog());
        shareMetadataButton.setOnClickListener(v -> shareMetadataFile());
        playbackSlider.addOnChangeListener((slider, value, fromUser) -> {
            if (!fromUser || updatingPlaybackSlider) {
                return;
            }
            selectedPlaybackPositionMs = Math.max(0, Math.round(value));
            updatePlaybackTimes(selectedPlaybackPositionMs, resolvePlaybackDurationMs());
        });
        playbackSlider.addOnSliderTouchListener(new Slider.OnSliderTouchListener() {
            @Override
            public void onStartTrackingTouch(Slider slider) {
                userSeekingPlayback = true;
            }

            @Override
            public void onStopTrackingTouch(Slider slider) {
                userSeekingPlayback = false;
                selectedPlaybackPositionMs = Math.max(0, Math.round(slider.getValue()));
                if (mediaPlayer != null && activePlaybackFileName != null
                        && activePlaybackFileName.equals(selectedPlaybackFileName)) {
                    mediaPlayer.seekTo(selectedPlaybackPositionMs);
                }
                syncPlaybackProgress(false);
            }
        });

        MaterialToolbar toolbar = findViewById(R.id.detail_toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        bindSessionViews();
    }

    @Override
    protected void onDestroy() {
        releasePlayback(true, true);
        super.onDestroy();
    }

    private void bindSessionViews() {
        TextView titleText = findViewById(R.id.detail_title);
        TextView badgeText = findViewById(R.id.detail_badge);
        TextView timestampText = findViewById(R.id.detail_timestamp_value);
        TextView durationText = findViewById(R.id.detail_duration_value);
        TextView fileSizeText = findViewById(R.id.detail_file_size_value);
        TextView speechRatioText = findViewById(R.id.detail_speech_ratio_value);
        TextView disturbanceText = findViewById(R.id.detail_disturbance_value);
        TextView reverbText = findViewById(R.id.detail_reverb_value);
        TextView timelineBodyText = findViewById(R.id.detail_timeline_body);
        TextView timelineLegendText = findViewById(R.id.detail_timeline_legend);
        TextView startTimeText = findViewById(R.id.detail_start_time);
        TextView endTimeText = findViewById(R.id.detail_end_time);
        TextView totalSpeechText = findViewById(R.id.detail_total_speech_value);
        TextView totalSilenceText = findViewById(R.id.detail_total_silence_value);
        TextView intervalCountText = findViewById(R.id.detail_interval_count_value);
        TextView longestSpeechText = findViewById(R.id.detail_longest_speech_value);
        SpeechTimelineView timelineView = findViewById(R.id.detail_timeline_graph);
        SpeechBucketChartView bucketChartView = findViewById(R.id.detail_bucket_chart);

        if (sessionItem == null) {
            titleText.setText(R.string.detail_title_fallback);
            badgeText.setText(R.string.detail_session_badge);
            timestampText.setText(R.string.detail_unknown_time);
            durationText.setText(R.string.detail_unknown_time);
            fileSizeText.setText(R.string.detail_unknown_time);
            speechRatioText.setText("0%");
            disturbanceText.setText("0");
            reverbText.setText("LOW");
            timelineBodyText.setText(R.string.detail_error_body);
            timelineLegendText.setVisibility(View.GONE);
            startTimeText.setText(R.string.detail_unknown_time);
            endTimeText.setText(R.string.detail_unknown_time);
            totalSpeechText.setText(getString(R.string.detail_total_speech_format, getString(R.string.detail_unknown_time)));
            totalSilenceText.setText(getString(R.string.detail_total_silence_format, getString(R.string.detail_unknown_time)));
            intervalCountText.setText(getString(R.string.detail_interval_count_format, 0));
            longestSpeechText.setText(getString(R.string.detail_longest_speech_format, getString(R.string.detail_unknown_time)));
            playRawButton.setEnabled(false);
            playFilteredButton.setEnabled(false);
            shareRawButton.setEnabled(false);
            shareFilteredButton.setEnabled(false);
            renameButton.setEnabled(false);
            deleteButton.setEnabled(false);
            shareMetadataButton.setEnabled(false);
            playbackSlider.setEnabled(false);
            playbackSourceLabel.setText(R.string.detail_playback_source_idle);
            updatePlaybackTimes(0, 0);
            actionHintText.setText(R.string.detail_action_hint_playback_unavailable);
            return;
        }

        titleText.setText(sessionItem.getTitle());
        badgeText.setText(R.string.detail_session_badge);
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
        startTimeText.setText(getString(R.string.detail_timeline_start));
        endTimeText.setText(
                getString(
                        R.string.detail_timeline_end,
                        AudioSessionFormatter.formatClockDuration(sessionItem.getDurationMillis())
                )
        );

        if (sessionMetadata == null) {
            timelineBodyText.setText(R.string.detail_error_body);
            timelineLegendText.setVisibility(View.GONE);
            totalSpeechText.setText(getString(R.string.detail_total_speech_format, getString(R.string.detail_unknown_time)));
            totalSilenceText.setText(getString(R.string.detail_total_silence_format, getString(R.string.detail_unknown_time)));
            intervalCountText.setText(getString(R.string.detail_interval_count_format, 0));
            longestSpeechText.setText(getString(R.string.detail_longest_speech_format, getString(R.string.detail_unknown_time)));
            bucketChartView.setData(java.util.Collections.emptyList(), 1L);
            timelineView.setData(java.util.Collections.emptyList(), 1L);
            actionHintText.setText(R.string.detail_action_hint_playback_unavailable);
        } else {
            if (sessionMetadata.getRoleIntervals().isEmpty()) {
                timelineBodyText.setText(getString(
                        R.string.detail_timeline_body_format,
                        sessionMetadata.getIntervalCount(),
                        Math.round(sessionMetadata.getSpeakingRatio() * 100.0f)
                ));
                timelineLegendText.setVisibility(View.GONE);
            } else {
                timelineBodyText.setText(getString(
                        R.string.detail_role_timeline_body_format,
                        AudioSessionFormatter.formatDuration(sessionMetadata.getTotalInstructorMillis()),
                        AudioSessionFormatter.formatDuration(sessionMetadata.getTotalStudentMillis()),
                        AudioSessionFormatter.formatDuration(sessionMetadata.getTotalBothMillis())
                ));
                timelineLegendText.setVisibility(View.VISIBLE);
            }
            totalSpeechText.setText(getString(
                    R.string.detail_total_speech_format,
                    AudioSessionFormatter.formatDuration(sessionMetadata.getTotalSpeechMillis())
            ));
            totalSilenceText.setText(getString(
                    R.string.detail_total_silence_format,
                    AudioSessionFormatter.formatDuration(sessionMetadata.getTotalSilenceMillis())
            ));
            intervalCountText.setText(getString(
                    R.string.detail_interval_count_format,
                    sessionMetadata.getIntervalCount()
            ));
            longestSpeechText.setText(getString(
                    R.string.detail_longest_speech_format,
                    AudioSessionFormatter.formatDuration(sessionMetadata.getLongestSpeechMillis())
            ));
            if (sessionMetadata.getRoleIntervals().isEmpty()) {
                timelineView.setData(sessionMetadata.getSpeechIntervals(), sessionMetadata.getDurationMillis());
            } else {
                timelineView.setRoleData(sessionMetadata.getRoleIntervals(), sessionMetadata.getDurationMillis());
            }
            bucketChartView.setData(sessionMetadata.getSpeechIntervals(), sessionMetadata.getDurationMillis());
            actionHintText.setText(
                    hasPlaybackAudio()
                            ? R.string.detail_action_hint
                            : R.string.detail_action_hint_playback_unavailable
            );
        }

        renameButton.setEnabled(true);
        deleteButton.setEnabled(true);
        shareMetadataButton.setEnabled(true);
        if (selectedPlaybackFileName == null) {
            File conditionedAudioFile = resolveConditionedAudioFile();
            File rawAudioFile = resolveRawAudioFile();
            if (conditionedAudioFile != null && conditionedAudioFile.exists()) {
                selectedPlaybackFileName = conditionedAudioFile.getName();
                selectedPlaybackLabelRes = R.string.detail_playback_source_filtered;
            } else if (rawAudioFile != null && rawAudioFile.exists()) {
                selectedPlaybackFileName = rawAudioFile.getName();
                selectedPlaybackLabelRes = R.string.detail_playback_source_raw;
            }
        }
        updatePlaybackButtons();
        syncPlaybackProgress(true);
    }

    private void showRenameDialog() {
        if (sessionItem == null) {
            return;
        }

        EditText editText = new EditText(this);
        editText.setText(sessionItem.getTitle());
        editText.setSelection(editText.getText().length());
        editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.detail_rename_dialog_title)
                .setView(editText)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.detail_rename, (dialog, which) -> {
                    String updatedTitle = editText.getText() == null
                            ? ""
                            : editText.getText().toString().trim();
                    if (updatedTitle.isEmpty()) {
                        Snackbar.make(
                                findViewById(R.id.detail_root),
                                R.string.detail_rename_empty,
                                Snackbar.LENGTH_SHORT
                        ).show();
                        return;
                    }

                    releasePlayback(true, false);
                    AudioSessionItem renamedItem = AudioLibraryRepository.getInstance().renameSession(
                            this,
                            sessionItem.getId(),
                            updatedTitle
                    );
                    if (renamedItem == null) {
                        Snackbar.make(
                                findViewById(R.id.detail_root),
                                R.string.detail_action_failed,
                                Snackbar.LENGTH_SHORT
                        ).show();
                        return;
                    }

                    sessionItem = renamedItem;
                    sessionMetadata = AudioLibraryRepository.getInstance().getSessionMetadata(this, sessionItem);
                    bindSessionViews();
                })
                .show();
    }

    private void showDeleteDialog() {
        if (sessionItem == null) {
            return;
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.detail_delete_confirm_title)
                .setMessage(R.string.detail_delete_confirm_body)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.detail_delete, (dialog, which) -> {
                    releasePlayback(true, false);
                    boolean deleted = AudioLibraryRepository.getInstance().deleteSession(
                            this,
                            sessionItem.getId()
                    );
                    if (!deleted) {
                        Snackbar.make(
                                findViewById(R.id.detail_root),
                                R.string.detail_action_failed,
                                Snackbar.LENGTH_SHORT
                        ).show();
                        return;
                    }
                    setResult(RESULT_OK);
                    finish();
                })
                .show();
    }

    private void shareMetadataFile() {
        if (sessionItem == null) {
            return;
        }

        File metadataFile = AudioLibraryRepository.getInstance().resolveMetadataFile(this, sessionItem);
        if (!metadataFile.exists()) {
            Snackbar.make(findViewById(R.id.detail_root), R.string.detail_share_failed, Snackbar.LENGTH_SHORT)
                    .show();
            return;
        }

        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("application/json");
        shareIntent.putExtra(
                Intent.EXTRA_STREAM,
                FileProvider.getUriForFile(
                        this,
                        getPackageName() + ".fileprovider",
                        metadataFile
                )
        );
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(shareIntent, getString(R.string.detail_share_json_chooser)));
    }

    private void shareAudioFile(File audioFile, int chooserResId) {
        if (audioFile == null || !audioFile.exists()) {
            Snackbar.make(findViewById(R.id.detail_root), R.string.detail_share_audio_failed, Snackbar.LENGTH_SHORT)
                    .show();
            return;
        }

        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("audio/wav");
        shareIntent.putExtra(
                Intent.EXTRA_STREAM,
                FileProvider.getUriForFile(
                        this,
                        getPackageName() + ".fileprovider",
                        audioFile
                )
        );
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(shareIntent, getString(chooserResId)));
    }

    private void togglePlayback(File audioFile, int sourceLabelRes) {
        if (audioFile == null || !audioFile.exists()) {
            playbackSourceLabel.setText(R.string.detail_playback_source_idle);
            selectedPlaybackFileName = null;
            selectedPlaybackLabelRes = R.string.detail_playback_source_idle;
            selectedPlaybackPositionMs = 0;
            syncPlaybackProgress(true);
            updatePlaybackButtons();
            Snackbar.make(findViewById(R.id.detail_root), R.string.detail_playback_missing, Snackbar.LENGTH_SHORT)
                    .show();
            return;
        }

        selectedPlaybackFileName = audioFile.getName();
        selectedPlaybackLabelRes = sourceLabelRes;
        playbackSourceLabel.setText(sourceLabelRes);

        if (audioFile.getName().equals(activePlaybackFileName) && mediaPlayer != null && mediaPlayer.isPlaying()) {
            pausePlayback();
            return;
        }

        try {
            releasePlayback(false, true);
            MediaPlayer player = new MediaPlayer();
            player.setDataSource(audioFile.getAbsolutePath());
            player.setOnPreparedListener(preparedPlayer -> {
                int seekPosition = Math.max(0, Math.min(selectedPlaybackPositionMs, preparedPlayer.getDuration()));
                if (seekPosition > 0) {
                    preparedPlayer.seekTo(seekPosition);
                }
                preparedPlayer.start();
                syncPlaybackProgress(false);
                playbackHandler.removeCallbacks(playbackProgressRunnable);
                playbackHandler.post(playbackProgressRunnable);
                updatePlaybackButtons();
            });
            player.setOnCompletionListener(completedPlayer -> {
                selectedPlaybackPositionMs = 0;
                releasePlayback(false, true);
                syncPlaybackProgress(false);
            });
            mediaPlayer = player;
            activePlaybackFileName = audioFile.getName();
            player.prepare();
        } catch (Exception exception) {
            releasePlayback(false, true);
            Snackbar.make(findViewById(R.id.detail_root), R.string.detail_playback_failed, Snackbar.LENGTH_SHORT)
                    .show();
        }
    }

    private void pausePlayback() {
        if (mediaPlayer == null) {
            updatePlaybackButtons();
            return;
        }

        selectedPlaybackPositionMs = mediaPlayer.getCurrentPosition();
        releasePlayback(false, true);
        syncPlaybackProgress(false);
    }

    private void releasePlayback(boolean clearSelection, boolean preservePosition) {
        playbackHandler.removeCallbacks(playbackProgressRunnable);
        if (mediaPlayer != null) {
            try {
                if (!preservePosition) {
                    selectedPlaybackPositionMs = 0;
                } else {
                    selectedPlaybackPositionMs = mediaPlayer.getCurrentPosition();
                }
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.pause();
                }
            } catch (IllegalStateException ignored) {
            }
            mediaPlayer.release();
            mediaPlayer = null;
        }
        activePlaybackFileName = null;
        if (clearSelection) {
            selectedPlaybackFileName = null;
            selectedPlaybackLabelRes = R.string.detail_playback_source_idle;
            selectedPlaybackPositionMs = 0;
        }
        updatePlaybackButtons();
    }

    private void updatePlaybackButtons() {
        File rawAudioFile = resolveRawAudioFile();
        File conditionedAudioFile = resolveConditionedAudioFile();
        boolean rawAvailable = rawAudioFile != null && rawAudioFile.exists();
        boolean conditionedAvailable = conditionedAudioFile != null && conditionedAudioFile.exists();
        boolean rawPlaying = rawAvailable
                && rawAudioFile.getName().equals(activePlaybackFileName)
                && mediaPlayer != null
                && mediaPlayer.isPlaying();
        boolean filteredPlaying = conditionedAvailable
                && conditionedAudioFile.getName().equals(activePlaybackFileName)
                && mediaPlayer != null
                && mediaPlayer.isPlaying();

        playRawButton.setEnabled(rawAvailable);
        playFilteredButton.setEnabled(conditionedAvailable);
        shareRawButton.setEnabled(rawAvailable);
        shareFilteredButton.setEnabled(conditionedAvailable);
        playRawButton.setText(rawPlaying ? R.string.detail_pause_raw : R.string.detail_play_raw);
        playFilteredButton.setText(filteredPlaying ? R.string.detail_pause_filtered : R.string.detail_play_filtered);
        playRawButton.setIconResource(rawPlaying ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);
        playFilteredButton.setIconResource(filteredPlaying ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);
        playbackSourceLabel.setText(selectedPlaybackLabelRes);
        playbackSlider.setEnabled(isSelectedPlaybackFileAvailable());
    }

    private void syncPlaybackProgress(boolean resetIfUnavailable) {
        int durationMs = resolvePlaybackDurationMs();
        if (selectedPlaybackFileName == null) {
            if (resetIfUnavailable) {
                selectedPlaybackPositionMs = 0;
            }
            updatePlaybackSlider(0, durationMs);
            updatePlaybackTimes(0, durationMs);
            return;
        }

        int currentPositionMs = selectedPlaybackPositionMs;
        if (mediaPlayer != null && activePlaybackFileName != null
                && activePlaybackFileName.equals(selectedPlaybackFileName) && !userSeekingPlayback) {
            currentPositionMs = mediaPlayer.getCurrentPosition();
            selectedPlaybackPositionMs = currentPositionMs;
        }

        if (resetIfUnavailable && !isSelectedPlaybackFileAvailable()) {
            selectedPlaybackPositionMs = 0;
            currentPositionMs = 0;
        }

        updatePlaybackSlider(currentPositionMs, durationMs);
        updatePlaybackTimes(currentPositionMs, durationMs);
    }

    private void updatePlaybackSlider(int currentPositionMs, int durationMs) {
        updatingPlaybackSlider = true;
        float boundedDuration = Math.max(1, durationMs);
        playbackSlider.setValueTo(boundedDuration);
        playbackSlider.setValue(Math.min(currentPositionMs, durationMs));
        updatingPlaybackSlider = false;
    }

    private void updatePlaybackTimes(int currentPositionMs, int durationMs) {
        playbackCurrentTimeText.setText(formatPlaybackTimestamp(currentPositionMs));
        playbackTotalTimeText.setText(formatPlaybackTimestamp(durationMs));
    }

    private int resolvePlaybackDurationMs() {
        if (mediaPlayer != null && activePlaybackFileName != null
                && activePlaybackFileName.equals(selectedPlaybackFileName)) {
            try {
                return Math.max(1, mediaPlayer.getDuration());
            } catch (IllegalStateException ignored) {
            }
        }
        if (sessionMetadata != null) {
            return (int) Math.max(1L, sessionMetadata.getDurationMillis());
        }
        if (sessionItem != null) {
            return (int) Math.max(1L, sessionItem.getDurationMillis());
        }
        return 1;
    }

    private boolean isSelectedPlaybackFileAvailable() {
        if (selectedPlaybackFileName == null) {
            return false;
        }
        File selectedFile = SessionAudioFileManager.resolveAudioFile(this, selectedPlaybackFileName);
        return selectedFile.exists();
    }

    private String formatPlaybackTimestamp(int positionMs) {
        int totalSeconds = Math.max(0, positionMs / 1000);
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;
        return String.format(java.util.Locale.US, "%d:%02d", minutes, seconds);
    }

    private boolean hasPlaybackAudio() {
        File rawAudioFile = resolveRawAudioFile();
        File conditionedAudioFile = resolveConditionedAudioFile();
        return rawAudioFile != null
                && rawAudioFile.exists()
                && conditionedAudioFile != null
                && conditionedAudioFile.exists();
    }

    private File resolveRawAudioFile() {
        if (sessionMetadata == null || sessionMetadata.getRawAudioFileName() == null) {
            return null;
        }
        return SessionAudioFileManager.resolveAudioFile(this, sessionMetadata.getRawAudioFileName());
    }

    private File resolveConditionedAudioFile() {
        if (sessionMetadata == null || sessionMetadata.getConditionedAudioFileName() == null) {
            return null;
        }
        return SessionAudioFileManager.resolveAudioFile(this, sessionMetadata.getConditionedAudioFileName());
    }
}
