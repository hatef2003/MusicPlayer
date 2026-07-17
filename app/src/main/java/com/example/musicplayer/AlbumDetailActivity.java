package com.example.musicplayer;

import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.example.musicplayer.model.Album;
import com.example.musicplayer.model.Song;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class AlbumDetailActivity extends BaseActivity {

    public static final String EXTRA_ALBUM_ID = "album_id";
    public static final String EXTRA_ALBUM_NAME = "album_name";
    public static final String EXTRA_ALBUM_ARTIST = "album_artist";

    private final List<Song> songs = new ArrayList<>();
    private SongAdapter adapter;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_album_detail);

        // Apply status bar inset to root layout, nav bar inset to song list
        applyStatusBarInset(findViewById(android.R.id.content));
        applyNavigationBarInset(findViewById(R.id.detailRecyclerView));

        long albumId = getIntent().getLongExtra(EXTRA_ALBUM_ID, -1);
        String albumName = getIntent().getStringExtra(EXTRA_ALBUM_NAME);
        String albumArtist = getIntent().getStringExtra(EXTRA_ALBUM_ARTIST);

        ImageView backBtn = findViewById(R.id.detailBackBtn);
        TextView nameView = findViewById(R.id.detailAlbumName);
        TextView artistView = findViewById(R.id.detailAlbumArtist);
        TextView metaView = findViewById(R.id.detailAlbumMeta);
        TextView emptyView = findViewById(R.id.emptyText);
        RecyclerView recyclerView = findViewById(R.id.detailRecyclerView);

        backBtn.setOnClickListener(v -> finish());

        nameView.setText(albumName != null ? albumName : "Album");
        artistView.setText(albumArtist != null ? albumArtist : "");

        ImageView albumArtView = findViewById(R.id.detailAlbumArt);
        Glide.with(this)
                .load(MusicRepository.getAlbumArtUri(albumId))
                .placeholder(R.drawable.ic_album)
                .error(R.drawable.ic_album)
                .centerCrop()
                .into(albumArtView);

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new SongAdapter(songs, song -> {
            int idx = songs.indexOf(song);
            PlaybackManager.getInstance(this).play(songs, idx);
        });
        recyclerView.setAdapter(adapter);

        // Load album songs
        Album loadedAlbum = null;
        List<Album> albums = MusicRepository.loadAlbums(this);
        for (Album a : albums) {
            if (a.getId() == albumId) {
                loadedAlbum = a;
                songs.addAll(a.getSongs());
                break;
            }
        }
        adapter.notifyDataSetChanged();

        // Show track count + duration
        if (loadedAlbum != null) {
            int count = loadedAlbum.getSongCount();
            int totalMin = (int) (loadedAlbum.getTotalDuration() / 60000);
            metaView.setText(String.format(Locale.US, "%d tracks \u2022 %d min", count, totalMin));
        }

        if (songs.isEmpty()) {
            emptyView.setVisibility(View.VISIBLE);
            emptyView.setText("// no songs in album");
        }

        // Highlight currently playing song
        Song current = PlaybackManager.getInstance(this).getCurrentSong();
        if (current != null) adapter.setPlayingSongId(current.getId());
    }

    @Override
    protected void onResume() {
        super.onResume();
        Song current = PlaybackManager.getInstance(this).getCurrentSong();
        if (current != null && adapter != null) adapter.setPlayingSongId(current.getId());
    }
}
