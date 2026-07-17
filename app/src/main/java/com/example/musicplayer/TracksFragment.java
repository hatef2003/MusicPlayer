package com.example.musicplayer;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.musicplayer.model.Song;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class TracksFragment extends Fragment {

    private SongAdapter adapter;
    private final List<Song> allSongs = new ArrayList<>();
    private final List<Song> filteredSongs = new ArrayList<>();
    private TextView emptyView;
    private TextView sortLabel;
    private String currentQuery = "";
    private SettingsManager.TracksSort currentSort = SettingsManager.TracksSort.TITLE;
    private SettingsManager settingsManager;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRetainInstance(true);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_list, container, false);
        emptyView = view.findViewById(R.id.emptyText);
        sortLabel = view.findViewById(R.id.sortLabel);
        emptyView.setText(getString(R.string.empty_tracks));
        RecyclerView recyclerView = view.findViewById(R.id.recyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new SongAdapter(filteredSongs, song -> {
            int idx = filteredSongs.indexOf(song);
            PlaybackManager.getInstance(requireContext()).play(filteredSongs, idx);
        });
        adapter.setOnLongClickListener(song -> {
            AddToPlaylistDialog dialog = AddToPlaylistDialog.newInstance(song);
            dialog.show(getParentFragmentManager(), "add_to_playlist");
        });
        recyclerView.setAdapter(adapter);

        if (sortLabel != null) {
            sortLabel.setOnClickListener(v -> showSortDialog());
        }

        loadSongs();
        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (getContext() == null) return;
        SettingsManager.TracksSort savedSort = getSettingsManager().getTracksSort();
        if (savedSort != currentSort) {
            currentSort = savedSort;
        }
        // Always reload from MediaStore so metadata changes (e.g. from AudD identify) are reflected
        loadSongs();
    }

    public void loadSongs() {
        if (getContext() == null) return;
        if (currentSort == null) {
            currentSort = getSettingsManager().getTracksSort();
        }
        allSongs.clear();
        allSongs.addAll(MusicRepository.loadAllSongs(getContext()));
        applyFilter();
    }

    private SettingsManager getSettingsManager() {
        if (settingsManager == null) {
            settingsManager = new SettingsManager(requireContext());
        }
        return settingsManager;
    }

    public void setSort(SettingsManager.TracksSort sort) {
        this.currentSort = sort;
        applyFilter();
    }

    /** Update which song is currently playing so the adapter can highlight it */
    public void updatePlayingSong(long songId) {
        if (adapter != null) adapter.setPlayingSongId(songId);
    }

    public void setFilterQuery(String query) {
        this.currentQuery = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        applyFilter();
    }

    private void applyFilter() {
        filteredSongs.clear();
        List<Song> source = currentQuery.isEmpty() ? allSongs : filterList(allSongs, currentQuery);
        filteredSongs.addAll(source);
        sortSongs(filteredSongs);
        if (adapter != null) adapter.notifyDataSetChanged();
        if (emptyView != null) {
            if (filteredSongs.isEmpty()) {
                emptyView.setText(currentQuery.isEmpty() ? getString(R.string.empty_tracks) : getString(R.string.empty_search));
                emptyView.setVisibility(View.VISIBLE);
            } else {
                emptyView.setVisibility(View.GONE);
            }
        }
        if (sortLabel != null) {
            String sortValue = getSortDisplayName(currentSort);
            String full = "Sort: " + sortValue + " ▼";
            android.text.SpannableString span = new android.text.SpannableString(full);
            span.setSpan(new android.text.style.ForegroundColorSpan(
                    getResources().getColor(R.color.terminal_green)), 0, 5, 0);
            span.setSpan(new android.text.style.ForegroundColorSpan(
                    getResources().getColor(R.color.text_primary)), 5, full.length(), 0);
            sortLabel.setText(span);
            sortLabel.setVisibility(View.VISIBLE);
        }
    }

    private List<Song> filterList(List<Song> source, String query) {
        List<Song> result = new ArrayList<>();
        for (Song song : source) {
            if (song.getTitle().toLowerCase(Locale.ROOT).contains(query)
                    || song.getArtist().toLowerCase(Locale.ROOT).contains(query)) {
                result.add(song);
            }
        }
        return result;
    }

    private void sortSongs(List<Song> list) {
        Comparator<Song> comparator;
        switch (currentSort) {
            case ARTIST:
                comparator = Comparator.comparing((Song s) -> s.getArtist().toLowerCase(Locale.ROOT))
                        .thenComparing((Song s) -> s.getTitle().toLowerCase(Locale.ROOT));
                break;
            case ALBUM:
                comparator = Comparator.comparing((Song s) -> s.getAlbum().toLowerCase(Locale.ROOT))
                        .thenComparing((Song s) -> s.getTitle().toLowerCase(Locale.ROOT));
                break;
            case DURATION:
                comparator = Comparator.comparingLong(Song::getDuration)
                        .thenComparing((Song s) -> s.getTitle().toLowerCase(Locale.ROOT));
                break;
            case DATE_ADDED:
                comparator = Comparator.comparingLong(Song::getDateAdded).reversed()
                        .thenComparing((Song s) -> s.getTitle().toLowerCase(Locale.ROOT));
                break;
            case TITLE:
            default:
                comparator = Comparator.comparing((Song s) -> s.getTitle().toLowerCase(Locale.ROOT));
                break;
        }
        Collections.sort(list, comparator);
    }

    private void showSortDialog() {
        if (getContext() == null) return;
        final SettingsManager.TracksSort[] sorts = {
                SettingsManager.TracksSort.TITLE,
                SettingsManager.TracksSort.ARTIST,
                SettingsManager.TracksSort.ALBUM,
                SettingsManager.TracksSort.DURATION,
                SettingsManager.TracksSort.DATE_ADDED
        };
        String[] items = {
                getString(R.string.sort_title),
                getString(R.string.sort_artist),
                getString(R.string.sort_album),
                getString(R.string.sort_duration),
                getString(R.string.sort_date_added)
        };
        int checked = 0;
        for (int i = 0; i < sorts.length; i++) {
            if (sorts[i] == currentSort) {
                checked = i;
                break;
            }
        }
        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle(R.string.sort_dialog_title)
                .setSingleChoiceItems(items, checked, (dialog, which) -> {
                    currentSort = sorts[which];
                    getSettingsManager().setTracksSort(currentSort);
                    applyFilter();
                    dialog.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private String getSortDisplayName(SettingsManager.TracksSort sort) {
        if (getContext() == null) return "";
        switch (sort) {
            case ARTIST: return getString(R.string.sort_artist);
            case ALBUM: return getString(R.string.sort_album);
            case DURATION: return getString(R.string.sort_duration);
            case DATE_ADDED: return getString(R.string.sort_date_added);
            default: return getString(R.string.sort_title);
        }
    }
}
