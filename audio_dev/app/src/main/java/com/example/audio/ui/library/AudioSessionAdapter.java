package com.example.audio.ui.library;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.audio.R;

import java.util.ArrayList;
import java.util.List;

public class AudioSessionAdapter extends RecyclerView.Adapter<AudioSessionAdapter.AudioSessionViewHolder> {

    public interface OnSessionClickListener {
        void onSessionClicked(AudioSessionItem item);
    }

    private final OnSessionClickListener listener;
    private final List<AudioSessionItem> items = new ArrayList<>();

    public AudioSessionAdapter(OnSessionClickListener listener) {
        this.listener = listener;
    }

    public void submitList(List<AudioSessionItem> newItems) {
        items.clear();
        items.addAll(newItems);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public AudioSessionViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_audio_session, parent, false);
        return new AudioSessionViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull AudioSessionViewHolder holder, int position) {
        AudioSessionItem item = items.get(position);
        holder.titleText.setText(item.getTitle());
        holder.timestampText.setText(AudioSessionFormatter.formatDateTime(item.getStartTimeMillis()));
        holder.rangeText.setText(AudioSessionFormatter.formatTimeRange(
                item.getStartTimeMillis(),
                item.getEndTimeMillis()
        ));
        holder.durationText.setText(
                holder.itemView.getContext().getString(
                        R.string.library_card_duration,
                        AudioSessionFormatter.formatDuration(item.getDurationMillis())
                )
        );
        holder.fileSizeText.setText(AudioSessionFormatter.formatFileSize(item.getFileSizeBytes()));
        holder.speechRatioText.setText(
                holder.itemView.getContext().getString(
                        R.string.library_card_speech_ratio,
                        Math.round(item.getSpeechRatio() * 100.0f)
                )
        );
        holder.disturbanceText.setText(
                holder.itemView.getContext().getString(
                        R.string.library_card_disturbance,
                        item.getDisturbanceCount()
                )
        );
        holder.reverbTagText.setText(
                holder.itemView.getContext().getString(
                        R.string.library_card_reverb,
                        item.getReverbLevel().name()
                )
        );
        holder.generatedFileText.setText(item.getGeneratedFilename());
        holder.openIcon.setAlpha(item.isPlaybackAvailable() ? 1.0f : 0.85f);
        holder.itemView.setOnClickListener(v -> listener.onSessionClicked(item));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static final class AudioSessionViewHolder extends RecyclerView.ViewHolder {

        private final TextView titleText;
        private final TextView generatedFileText;
        private final TextView timestampText;
        private final TextView rangeText;
        private final TextView durationText;
        private final TextView fileSizeText;
        private final TextView speechRatioText;
        private final TextView disturbanceText;
        private final TextView reverbTagText;
        private final ImageView openIcon;

        private AudioSessionViewHolder(@NonNull View itemView) {
            super(itemView);
            titleText = itemView.findViewById(R.id.audio_item_title);
            generatedFileText = itemView.findViewById(R.id.audio_item_filename);
            timestampText = itemView.findViewById(R.id.audio_item_timestamp);
            rangeText = itemView.findViewById(R.id.audio_item_time_range);
            durationText = itemView.findViewById(R.id.audio_item_duration);
            fileSizeText = itemView.findViewById(R.id.audio_item_file_size);
            speechRatioText = itemView.findViewById(R.id.audio_item_speech_ratio);
            disturbanceText = itemView.findViewById(R.id.audio_item_disturbance);
            reverbTagText = itemView.findViewById(R.id.audio_item_reverb);
            openIcon = itemView.findViewById(R.id.audio_item_open_icon);
        }
    }
}
