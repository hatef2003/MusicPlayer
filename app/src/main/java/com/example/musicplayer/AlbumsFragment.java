package com.example.musicplayer;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.musicplayer.model.Album;
import com.example.musicplayer.model.Song;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class AlbumsFragment extends Fragment {

    private AlbumAdapter adapter;
    private final List<Album> allAlbums = new ArrayList<>();
    private final List<Album> filteredAlbums = new ArrayList<>();
    private TextView emptyView;
    private String currentQuery = "";

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
        emptyView.setText(getString(R.string.empty_albums));
        RecyclerView recyclerView = view.findViewById(R.id.recyclerView);
        recyclerView.setLayoutManager(new GridLayoutManager(getContext(), 2));
        adapter = new AlbumAdapter(filteredAlbums, album -> {
            // Open AlbumDetailActivity
            android.content.Intent intent = new android.content.Intent(getContext(), AlbumDetailActivity.class);
            intent.putExtra(AlbumDetailActivity.EXTRA_ALBUM_ID, album.getId());
            intent.putExtra(AlbumDetailActivity.EXTRA_ALBUM_NAME, album.getName());
            intent.putExtra(AlbumDetailActivity.EXTRA_ALBUM_ARTIST, album.getArtist());
            startActivity(intent);
        });

        adapter.setOnAlbumMenuListener(new AlbumAdapter.OnAlbumMenuListener() {
            @Override
            public void onPlayAll(Album album) {
                List<Song> songs = album.getSongs();
                if (!songs.isEmpty()) {
                    PlaybackManager.getInstance(requireContext()).play(songs, 0);
                }
            }

            @Override
            public void onShufflePlay(Album album) {
                List<Song> songs = new ArrayList<>(album.getSongs());
                if (!songs.isEmpty()) {
                    Collections.shuffle(songs);
                    PlaybackManager.getInstance(requireContext()).play(songs, 0);
                }
            }

            @Override
            public void onGoToArtist(String artist) {
                // Filter the main activity's tracks/albums by artist
                if (getActivity() instanceof MainActivity) {
                    ((MainActivity) getActivity()).searchByArtist(artist);
                }
            }
        });

        recyclerView.setAdapter(adapter);
        loadAlbums();
        return view;
    }

    public void loadAlbums() {
        if (getContext() == null) return;
        allAlbums.clear();
        allAlbums.addAll(MusicRepository.loadAlbums(getContext()));
        applyFilter();
        updatePlayingIndicator();
    }

    public void updatePlayingIndicator() {
        if (adapter == null || getContext() == null) return;
        PlaybackManager pm = PlaybackManager.getInstance(getContext());
        Song current = pm.getCurrentSong();
        if (current != null) {
            // Find which album the current song belongs to
            for (Album album : allAlbums) {
                for (Song s : album.getSongs()) {
                    if (s.getId() == current.getId()) {
                        adapter.setPlayingAlbumId(album.getId());
                        return;
                    }
                }
            }
        }
        adapter.setPlayingAlbumId(-1);
    }

    public void setFilterQuery(String query) {
        this.currentQuery = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        applyFilter();
    }

    private void applyFilter() {
        filteredAlbums.clear();
        if (currentQuery.isEmpty()) {
            filteredAlbums.addAll(allAlbums);
        } else {
            for (Album album : allAlbums) {
                if (album.getName().toLowerCase(Locale.ROOT).contains(currentQuery)
                        || album.getArtist().toLowerCase(Locale.ROOT).contains(currentQuery)) {
                    filteredAlbums.add(album);
                }
            }
        }
        if (adapter != null) adapter.notifyDataSetChanged();
        if (emptyView != null) {
            if (filteredAlbums.isEmpty()) {
                emptyView.setText(currentQuery.isEmpty() ? getString(R.string.empty_albums) : getString(R.string.empty_search));
                emptyView.setVisibility(View.VISIBLE);
            } else {
                emptyView.setVisibility(View.GONE);
            }
        }
    }
}
