package com.example.audio.ui.detail;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.widget.EditText;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.audio.R;
import com.example.audio.data.SessionMetadata;
import com.example.audio.ui.library.AudioLibraryRepository;
import com.example.audio.ui.library.AudioSessionFormatter;
import com.example.audio.ui.library.AudioSessionItem;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import java.io.File;

public class AudioDetailActivity extends AppCompatActivity {

    private static final String EXTRA_SESSION_ITEM = "extra_session_item";

    private AudioSessionItem sessionItem;
    private SessionMetadata sessionMetadata;

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

        MaterialToolbar toolbar = findViewById(R.id.detail_toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        bindSessionViews();
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
        TextView startTimeText = findViewById(R.id.detail_start_time);
        TextView endTimeText = findViewById(R.id.detail_end_time);
        TextView totalSpeechText = findViewById(R.id.detail_total_speech_value);
        TextView totalSilenceText = findViewById(R.id.detail_total_silence_value);
        TextView intervalCountText = findViewById(R.id.detail_interval_count_value);
        TextView longestSpeechText = findViewById(R.id.detail_longest_speech_value);
        SpeechTimelineView timelineView = findViewById(R.id.detail_timeline_graph);
        SpeechBucketChartView bucketChartView = findViewById(R.id.detail_bucket_chart);
        MaterialButton renameButton = findViewById(R.id.detail_rename_button);
        MaterialButton deleteButton = findViewById(R.id.detail_delete_button);
        MaterialButton shareButton = findViewById(R.id.detail_share_button);

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
            startTimeText.setText(R.string.detail_unknown_time);
            endTimeText.setText(R.string.detail_unknown_time);
            totalSpeechText.setText(getString(R.string.detail_total_speech_format, getString(R.string.detail_unknown_time)));
            totalSilenceText.setText(getString(R.string.detail_total_silence_format, getString(R.string.detail_unknown_time)));
            intervalCountText.setText(getString(R.string.detail_interval_count_format, 0));
            longestSpeechText.setText(getString(R.string.detail_longest_speech_format, getString(R.string.detail_unknown_time)));
            renameButton.setEnabled(false);
            deleteButton.setEnabled(false);
            shareButton.setEnabled(false);
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
            totalSpeechText.setText(getString(R.string.detail_total_speech_format, getString(R.string.detail_unknown_time)));
            totalSilenceText.setText(getString(R.string.detail_total_silence_format, getString(R.string.detail_unknown_time)));
            intervalCountText.setText(getString(R.string.detail_interval_count_format, 0));
            longestSpeechText.setText(getString(R.string.detail_longest_speech_format, getString(R.string.detail_unknown_time)));
            bucketChartView.setData(java.util.Collections.emptyList(), 1L);
            timelineView.setData(java.util.Collections.emptyList(), 1L);
        } else {
            timelineBodyText.setText(getString(
                    R.string.detail_timeline_body_format,
                    sessionMetadata.getIntervalCount(),
                    Math.round(sessionMetadata.getSpeakingRatio() * 100.0f)
            ));
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
            timelineView.setData(sessionMetadata.getSpeechIntervals(), sessionMetadata.getDurationMillis());
            bucketChartView.setData(sessionMetadata.getSpeechIntervals(), sessionMetadata.getDurationMillis());
        }

        renameButton.setOnClickListener(v -> showRenameDialog());
        deleteButton.setOnClickListener(v -> showDeleteDialog());
        shareButton.setOnClickListener(v -> shareMetadataFile());
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
        startActivity(Intent.createChooser(shareIntent, getString(R.string.detail_share_audio_chooser)));
    }
}
