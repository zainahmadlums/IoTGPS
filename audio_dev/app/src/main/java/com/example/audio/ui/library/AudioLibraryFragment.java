package com.example.audio.ui.library;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.audio.R;
import com.example.audio.ui.detail.AudioDetailActivity;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.chip.Chip;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class AudioLibraryFragment extends Fragment implements AudioSessionAdapter.OnSessionClickListener {

    private TextInputEditText searchInput;
    private MaterialAutoCompleteTextView sortInput;
    private Chip allChip;
    private Chip todayChip;
    private Chip speechHeavyChip;
    private Chip disturbanceChip;
    private RecyclerView recyclerView;
    private View emptyStateView;
    private TextView sampleBannerText;
    private MaterialButton deleteAllButton;
    private AudioSessionAdapter adapter;
    private List<AudioSessionItem> allSessions = new ArrayList<>();

    public AudioLibraryFragment() {
        super(R.layout.fragment_audio_library);
    }

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState
    ) {
        return inflater.inflate(R.layout.fragment_audio_library, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        searchInput = view.findViewById(R.id.library_search_input);
        sortInput = view.findViewById(R.id.library_sort_input);
        allChip = view.findViewById(R.id.filter_chip_all);
        todayChip = view.findViewById(R.id.filter_chip_today);
        speechHeavyChip = view.findViewById(R.id.filter_chip_speech_heavy);
        disturbanceChip = view.findViewById(R.id.filter_chip_disturbance);
        recyclerView = view.findViewById(R.id.audio_library_recycler);
        emptyStateView = view.findViewById(R.id.library_empty_state);
        sampleBannerText = view.findViewById(R.id.library_sample_banner);
        deleteAllButton = view.findViewById(R.id.library_delete_all_button);

        adapter = new AudioSessionAdapter(this);
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerView.setAdapter(adapter);

        ArrayAdapter<CharSequence> sortAdapter = new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_list_item_1,
                getResources().getStringArray(R.array.audio_sort_options)
        );
        sortInput.setAdapter(sortAdapter);
        sortInput.setText(getString(R.string.audio_sort_default), false);

        searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                renderLibrary();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        View.OnClickListener filterListener = v -> renderLibrary();
        allChip.setOnClickListener(filterListener);
        todayChip.setOnClickListener(filterListener);
        speechHeavyChip.setOnClickListener(filterListener);
        disturbanceChip.setOnClickListener(filterListener);
        sortInput.setOnItemClickListener((parent, v, position, id) -> renderLibrary());
        deleteAllButton.setOnClickListener(v -> showDeleteAllDialog());

        allChip.setChecked(true);
        sampleBannerText.setText(R.string.library_recordings_heading);
        refreshLibrary();
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshLibrary();
    }

    @Override
    public void onSessionClicked(AudioSessionItem item) {
        Intent intent = AudioDetailActivity.createIntent(requireContext(), item);
        startActivity(intent);
    }

    private void refreshLibrary() {
        allSessions = AudioLibraryRepository.getInstance().getSessions(requireContext());
        renderLibrary();
    }

    private void renderLibrary() {
        List<AudioSessionItem> filteredSessions = new ArrayList<>();
        String query = searchInput.getText() == null
                ? ""
                : searchInput.getText().toString().trim().toLowerCase(Locale.US);
        String sortSelection = sortInput.getText() == null
                ? getString(R.string.audio_sort_default)
                : sortInput.getText().toString();

        for (AudioSessionItem item : allSessions) {
            if (!matchesFilter(item)) {
                continue;
            }
            if (!query.isEmpty()
                    && !item.getTitle().toLowerCase(Locale.US).contains(query)
                    && !item.getGeneratedFilename().toLowerCase(Locale.US).contains(query)) {
                continue;
            }
            filteredSessions.add(item);
        }

        sortSessions(filteredSessions, sortSelection);
        adapter.submitList(filteredSessions);
        emptyStateView.setVisibility(filteredSessions.isEmpty() ? View.VISIBLE : View.GONE);
        recyclerView.setVisibility(filteredSessions.isEmpty() ? View.GONE : View.VISIBLE);
        deleteAllButton.setEnabled(!allSessions.isEmpty());
        deleteAllButton.setAlpha(allSessions.isEmpty() ? 0.5f : 1.0f);
    }

    private boolean matchesFilter(AudioSessionItem item) {
        if (todayChip.isChecked()) {
            return AudioSessionFormatter.isToday(item.getStartTimeMillis());
        }
        if (speechHeavyChip.isChecked()) {
            return item.getSpeechRatio() >= 0.6f;
        }
        if (disturbanceChip.isChecked()) {
            return item.getDisturbanceCount() > 0;
        }
        return true;
    }

    private void sortSessions(List<AudioSessionItem> items, String sortSelection) {
        Comparator<AudioSessionItem> comparator;
        if (getString(R.string.library_sort_oldest).equals(sortSelection)) {
            comparator = Comparator.comparingLong(AudioSessionItem::getStartTimeMillis);
        } else if (getString(R.string.library_sort_longest).equals(sortSelection)) {
            comparator = (left, right) -> Long.compare(
                    right.getDurationMillis(),
                    left.getDurationMillis()
            );
        } else {
            comparator = (left, right) -> Long.compare(
                    right.getStartTimeMillis(),
                    left.getStartTimeMillis()
            );
        }

        Collections.sort(items, comparator);
    }

    private void showDeleteAllDialog() {
        if (allSessions.isEmpty()) {
            return;
        }

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.library_delete_all_title)
                .setMessage(R.string.library_delete_all_body)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.library_delete_all_action, (dialog, which) -> {
                    boolean deleted = AudioLibraryRepository.getInstance().deleteAllSessions(requireContext());
                    if (!deleted) {
                        Snackbar.make(
                                requireView(),
                                R.string.detail_action_failed,
                                Snackbar.LENGTH_SHORT
                        ).show();
                        return;
                    }
                    refreshLibrary();
                    Snackbar.make(
                            requireView(),
                            R.string.library_delete_all_done,
                            Snackbar.LENGTH_SHORT
                    ).show();
                })
                .show();
    }
}
