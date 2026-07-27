package com.example.musicplayer;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
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

    // Mini player views
    private LinearLayout miniPlayer;
    private TextView miniTitle, miniArtist;
    private ImageView miniPlayPause, miniAlbumArt;
    private SeekBar miniSeekBar;
    private boolean miniUserDragging = false;

    // Mini player progress updater
    private final Handler miniHandler = new Handler(Looper.getMainLooper());
    private final Runnable miniProgressUpdater = new Runnable() {
        @Override
        public void run() {
            PlaybackManager pm = PlaybackManager.getInstance(AlbumDetailActivity.this);
            if (pm.isPlaying() && !miniUserDragging) {
                int dur = pm.getDuration();
                int pos = pm.getPosition();
                if (dur > 0) {
                    miniSeekBar.setMax(dur);
                    miniSeekBar.setProgress(pos);
                }
            }
            miniHandler.postDelayed(this, 500);
        }
    };

    // Real-time playback callback — updates mini player + highlights the current song
    private final PlaybackManager.Callback playbackCallback = (isPlaying, currentSong, position, duration) ->
            runOnUiThread(() -> {
                updateMiniPlayer(isPlaying, currentSong, position, duration);
                // Update playing-song highlight in the album's song list
                if (adapter != null) {
                    adapter.setPlayingSongId(currentSong != null ? currentSong.getId() : -1);
                }
            });

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

        // ── Mini player views ──
        miniPlayer = findViewById(R.id.detailMiniPlayer);
        miniTitle = findViewById(R.id.detailMiniTitle);
        miniArtist = findViewById(R.id.detailMiniArtist);
        miniPlayPause = findViewById(R.id.detailMiniPlayPause);
        miniAlbumArt = findViewById(R.id.detailMiniAlbumArt);
        miniSeekBar = findViewById(R.id.detailMiniSeekBar);

        // Apply navigation bar inset to the mini player so it isn't hidden by OS keys
        applyNavigationBarInset(miniPlayer);

        miniPlayPause.setOnClickListener(v -> {
            PlaybackManager pm = PlaybackManager.getInstance(this);
            if (pm.isPlaying()) pm.pause(); else pm.resume();
        });

        miniPlayer.setOnClickListener(v -> {
            if (PlaybackManager.getInstance(this).getCurrentSong() != null) {
                startActivity(new Intent(this, NowPlayingActivity.class));
            }
        });

        miniSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {}
            @Override public void onStartTrackingTouch(SeekBar sb) { miniUserDragging = true; }
            @Override public void onStopTrackingTouch(SeekBar sb) {
                miniUserDragging = false;
                PlaybackManager.getInstance(AlbumDetailActivity.this).seekTo(sb.getProgress());
            }
        });

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

        // ⋮ overflow menu → Tech UI context menu for album songs
        adapter.setOnSongMenuClickListener((song, anchor) -> {
            FavoritesManager favMgr = new FavoritesManager(this);
            boolean isFav = favMgr.isFavorite(song.getId());
            new TechContextMenu.Builder(this)
                    .setSong(song)
                    .addItem(R.drawable.ic_menu_add_playlist, "Add to Playlist", () -> {
                        AddToPlaylistDialog dialog = AddToPlaylistDialog.newInstance(song);
                        dialog.show(getSupportFragmentManager(), "add_to_playlist");
                    })
                    .addItem(R.drawable.ic_menu_favorite, isFav ? "Unfavorite" : "Favorite", () -> {
                        boolean nowFav = favMgr.toggleFavorite(song.getId());
                        android.widget.Toast.makeText(this,
                                nowFav ? "Added to favorites" : "Removed from favorites",
                                android.widget.Toast.LENGTH_SHORT).show();
                    })
                    .addSpacer()
                    .addItem(R.drawable.ic_menu_details, "Track Details", () -> {
                        new TrackDetailsDialog(this, song).show();
                    })
                    .addItem(R.drawable.ic_menu_share, "Share", () -> {
                        SongActionsHelper.shareSong(this, song);
                    })
                    .addItem(R.drawable.ic_menu_ringtone, "Set as Ringtone", () -> {
                        SongActionsHelper.setAsRingtone(this, song);
                    })
                    .addSpacer()
                    .addDestructiveItem(R.drawable.ic_menu_delete, "Delete", () -> {
                        SongActionsHelper.deleteSong(this, song, () -> {
                            songs.remove(song);
                            adapter.notifyDataSetChanged();
                        });
                    })
                    .show(anchor);
        });

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

        // Initial mini player state (if something is already playing elsewhere)
        if (current != null) {
            updateMiniPlayer(PlaybackManager.getInstance(this).isPlaying(), current,
                    PlaybackManager.getInstance(this).getPosition(),
                    PlaybackManager.getInstance(this).getDuration());
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        PlaybackManager pm = PlaybackManager.getInstance(this);
        pm.bind();
        pm.addCallback(playbackCallback);
        miniHandler.post(miniProgressUpdater);
        // Sync highlight with current playback when returning to this screen
        Song current = pm.getCurrentSong();
        if (adapter != null) {
            adapter.setPlayingSongId(current != null ? current.getId() : -1);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Song current = PlaybackManager.getInstance(this).getCurrentSong();
        if (current != null && adapter != null) adapter.setPlayingSongId(current.getId());
    }

    @Override
    protected void onStop() {
        super.onStop();
        miniHandler.removeCallbacks(miniProgressUpdater);
        PlaybackManager.getInstance(this).removeCallback(playbackCallback);
    }

    /** Update the mini player bar at the bottom of the album detail screen. */
    private void updateMiniPlayer(boolean isPlaying, Song currentSong, int position, int duration) {
        if (currentSong == null) {
            miniPlayer.setVisibility(View.GONE);
            return;
        }
        miniPlayer.setVisibility(View.VISIBLE);
        miniTitle.setText(currentSong.getTitle());
        miniArtist.setText(currentSong.getArtist());
        miniPlayPause.setImageResource(isPlaying ? R.drawable.ic_pause : R.drawable.ic_play);
        Glide.with(this)
                .load(MusicRepository.getAlbumArtUri(currentSong.getAlbumId()))
                .placeholder(R.drawable.ic_album).error(R.drawable.ic_album)
                .centerCrop().into(miniAlbumArt);
        if (duration > 0 && !miniUserDragging) {
            miniSeekBar.setMax(duration);
            miniSeekBar.setProgress(position);
        }
    }
}
