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

import java.util.ArrayList;
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
        recyclerView.setAdapter(adapter);
        loadAlbums();
        return view;
    }

    public void loadAlbums() {
        if (getContext() == null) return;
        allAlbums.clear();
        allAlbums.addAll(MusicRepository.loadAlbums(getContext()));
        applyFilter();
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
