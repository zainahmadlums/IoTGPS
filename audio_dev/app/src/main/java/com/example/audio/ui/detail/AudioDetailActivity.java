package com.example.audio.ui.detail;

import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import com.example.audio.ui.library.AudioLibraryRepository;
import com.example.audio.ui.library.AudioSessionFormatter;
import com.example.audio.ui.library.AudioSessionItem;
import com.example.audio.util.Logger;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.snackbar.Snackbar;

import java.io.File;
import java.io.IOException;

public class AudioDetailActivity extends AppCompatActivity {

    private static final String TAG = "AudioDetailActivity";
    private static final String EXTRA_SESSION_ITEM = "extra_session_item";
    private static final long PROGRESS_UPDATE_MILLIS = 250L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private AudioSessionItem sessionItem;
    private MaterialButton playPauseButton;
    private LinearProgressIndicator progressIndicator;
    private TextView currentTimeText;
    private TextView totalTimeText;
    private TextView playbackBodyText;
    private MediaPlayer mediaPlayer;
    private boolean mediaPrepared;
    private final Runnable progressUpdater = new Runnable() {
        @Override
        public void run() {
            if (mediaPlayer == null || !mediaPrepared) {
                return;
            }
            renderPlayback();
            if (mediaPlayer.isPlaying()) {
                handler.postDelayed(this, PROGRESS_UPDATE_MILLIS);
            }
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

        bindSessionViews();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(progressUpdater);
        releaseMediaPlayer();
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
        currentTimeText = findViewById(R.id.detail_current_time);
        totalTimeText = findViewById(R.id.detail_total_time);
        playbackBodyText = findViewById(R.id.detail_unavailable_body);
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
            playbackBodyText.setText(R.string.detail_error_body);
            playPauseButton.setEnabled(false);
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

        progressIndicator.setMax((int) Math.max(1L, sessionItem.getDurationMillis()));
        progressIndicator.setProgress(0);
        currentTimeText.setText(AudioSessionFormatter.formatClockDuration(0L));
        totalTimeText.setText(AudioSessionFormatter.formatClockDuration(sessionItem.getDurationMillis()));

        playPauseButton.setEnabled(sessionItem.isPlaybackAvailable());
        playPauseButton.setText(R.string.detail_play);
        playbackBodyText.setText(sessionItem.isPlaybackAvailable()
                ? R.string.detail_playback_ready
                : R.string.detail_playback_unavailable);

        playPauseButton.setOnClickListener(v -> togglePlayback());
        renameButton.setOnClickListener(v -> showRenameDialog());
        deleteButton.setOnClickListener(v -> showDeleteDialog());
        shareButton.setOnClickListener(v -> shareAudioFile());
    }

    private void togglePlayback() {
        if (sessionItem == null || !sessionItem.isPlaybackAvailable()) {
            return;
        }

        if (mediaPlayer == null) {
            prepareMediaPlayer();
            return;
        }

        if (!mediaPrepared) {
            return;
        }

        if (mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
            handler.removeCallbacks(progressUpdater);
            playPauseButton.setText(R.string.detail_play);
        } else {
            startPlayback();
        }
    }

    private void prepareMediaPlayer() {
        File audioFile = AudioLibraryRepository.getInstance().resolveAudioFile(this, sessionItem);
        Logger.d(
                TAG,
                "Preparing playback path="
                        + audioFile.getAbsolutePath()
                        + ", exists="
                        + audioFile.exists()
                        + ", fileSize="
                        + audioFile.length()
        );
        if (!audioFile.exists()) {
            playbackBodyText.setText(R.string.detail_error_body);
            playPauseButton.setEnabled(false);
            Snackbar.make(findViewById(R.id.detail_root), R.string.detail_playback_failed, Snackbar.LENGTH_SHORT)
                    .show();
            return;
        }

        releaseMediaPlayer();
        mediaPlayer = new MediaPlayer();
        mediaPrepared = false;
        playPauseButton.setEnabled(false);
        playbackBodyText.setText(R.string.detail_loading_body);

        try {
            mediaPlayer.setAudioAttributes(
                    new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
            );
            mediaPlayer.setVolume(1.0f, 1.0f);
            mediaPlayer.setDataSource(audioFile.getAbsolutePath());
            mediaPlayer.setOnPreparedListener(player -> {
                mediaPrepared = true;
                playPauseButton.setEnabled(true);
                playbackBodyText.setText(R.string.detail_playback_ready);
                Logger.d(
                        TAG,
                        "Playback prepared path="
                                + audioFile.getAbsolutePath()
                                + ", durationMs="
                                + player.getDuration()
                );
                if (player.getDuration() > 0) {
                    progressIndicator.setMax(player.getDuration());
                    totalTimeText.setText(AudioSessionFormatter.formatClockDuration(player.getDuration()));
                }
                startPlayback();
            });
            mediaPlayer.setOnCompletionListener(player -> {
                handler.removeCallbacks(progressUpdater);
                progressIndicator.setProgress(0);
                currentTimeText.setText(AudioSessionFormatter.formatClockDuration(0L));
                playPauseButton.setText(R.string.detail_play);
                player.seekTo(0);
            });
            mediaPlayer.setOnErrorListener((player, what, extra) -> {
                playbackBodyText.setText(R.string.detail_error_body);
                releaseMediaPlayer();
                Snackbar.make(
                        findViewById(R.id.detail_root),
                        R.string.detail_playback_failed,
                        Snackbar.LENGTH_SHORT
                ).show();
                return true;
            });
            mediaPlayer.prepareAsync();
        } catch (IOException ioException) {
            playbackBodyText.setText(R.string.detail_error_body);
            releaseMediaPlayer();
            Snackbar.make(findViewById(R.id.detail_root), R.string.detail_playback_failed, Snackbar.LENGTH_SHORT)
                    .show();
        }
    }

    private void startPlayback() {
        if (mediaPlayer == null || !mediaPrepared) {
            return;
        }
        mediaPlayer.start();
        playPauseButton.setText(R.string.detail_pause);
        handler.removeCallbacks(progressUpdater);
        handler.post(progressUpdater);
    }

    private void renderPlayback() {
        if (mediaPlayer == null || !mediaPrepared) {
            return;
        }
        int currentPosition = mediaPlayer.getCurrentPosition();
        progressIndicator.setProgress(currentPosition);
        currentTimeText.setText(AudioSessionFormatter.formatClockDuration(currentPosition));
    }

    private void releaseMediaPlayer() {
        if (mediaPlayer != null) {
            mediaPlayer.reset();
            mediaPlayer.release();
            mediaPlayer = null;
        }
        mediaPrepared = false;
        handler.removeCallbacks(progressUpdater);
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

                    releaseMediaPlayer();
                    sessionItem = renamedItem;
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
                    releaseMediaPlayer();
                    setResult(RESULT_OK);
                    finish();
                })
                .show();
    }

    private void shareAudioFile() {
        if (sessionItem == null) {
            return;
        }

        File audioFile = AudioLibraryRepository.getInstance().resolveAudioFile(this, sessionItem);
        if (!audioFile.exists()) {
            Snackbar.make(findViewById(R.id.detail_root), R.string.detail_share_failed, Snackbar.LENGTH_SHORT)
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
        startActivity(Intent.createChooser(shareIntent, getString(R.string.detail_share_audio_chooser)));
    }
}
