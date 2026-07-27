package com.example.musicplayer;

import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.musicplayer.model.Playlist;
import com.example.musicplayer.model.Song;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Activity showing songs inside a specific playlist.
 */
public class PlaylistDetailActivity extends BaseActivity {

    private long playlistId;
    private boolean isFavorites;
    private PlaylistManager playlistManager;
    private SongAdapter adapter;
    private List<Song> playlistSongs = new ArrayList<>();
    private List<Song> allSongs = new ArrayList<>();
    private TextView titleView, infoView, emptyView;
    private RecyclerView recyclerView;

    private final ActivityResultLauncher<String> exportLauncher =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("audio/x-mpegurl"), uri -> {
                if (uri != null) exportPlaylistToUri(uri);
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_playlist_detail);

        // Apply status bar inset to root layout, nav bar inset to song list
        applyStatusBarInset(findViewById(android.R.id.content));
        applyNavigationBarInset(findViewById(R.id.plRecyclerView));

        playlistId = getIntent().getLongExtra("playlist_id", -1);
        isFavorites = getIntent().getBooleanExtra("is_favorites", false);
        if (playlistId == -1 && !isFavorites) {
            finish();
            return;
        }

        playlistManager = PlaylistManager.getInstance(this);

        titleView = findViewById(R.id.plTitle);
        infoView = findViewById(R.id.plInfo);
        emptyView = findViewById(R.id.plEmptyText);
        recyclerView = findViewById(R.id.plRecyclerView);
        ImageView backBtn = findViewById(R.id.plBackBtn);
        TextView playAllBtn = findViewById(R.id.plPlayAll);
        TextView exportBtn = findViewById(R.id.plExport);

        backBtn.setOnClickListener(v -> finish());

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new SongAdapter(playlistSongs, song -> {
            int idx = playlistSongs.indexOf(song);
            if (idx >= 0) {
                PlaybackManager.getInstance(this).play(playlistSongs, idx);
            }
        });
        recyclerView.setAdapter(adapter);

        playAllBtn.setOnClickListener(v -> {
            if (!playlistSongs.isEmpty()) {
                PlaybackManager.getInstance(this).play(playlistSongs, 0);
            }
        });

        // Hide export button for favorites (it's not a real playlist)
        if (isFavorites) {
            exportBtn.setVisibility(View.GONE);
        }

        exportBtn.setOnClickListener(v -> {
            Playlist playlist = playlistManager.getPlaylist(playlistId);
            if (playlist != null) {
                String filename = sanitizeFilename(playlist.getName()) + ".m3u";
                exportLauncher.launch(filename);
            }
        });

        // Long press on a song → show Tech UI menu
        adapter.setOnLongClickListener(song -> {
            FavoritesManager favMgr = new FavoritesManager(this);
            boolean isFav = favMgr.isFavorite(song.getId());
            TechContextMenu.Builder builder = new TechContextMenu.Builder(this)
                    .setSong(song)
                    .addItem(R.drawable.ic_menu_favorite, isFav ? "Unfavorite" : "Favorite", () -> {
                        boolean nowFav = favMgr.toggleFavorite(song.getId());
                        Toast.makeText(this,
                                nowFav ? "Added to favorites" : "Removed from favorites",
                                Toast.LENGTH_SHORT).show();
                        if (isFavorites) loadPlaylistSongs();
                    })
                    .addSpacer()
                    .addItem(R.drawable.ic_menu_details, "Track Details", () -> {
                        new TrackDetailsDialog(this, song).show();
                    });
            if (!isFavorites) {
                builder.addDestructiveItem(R.drawable.ic_menu_remove, "Remove from Playlist", () -> {
                    playlistManager.removeSongFromPlaylist(playlistId, song.getId());
                    loadPlaylistSongs();
                    Toast.makeText(this, "Removed from playlist", Toast.LENGTH_SHORT).show();
                });
            }
            builder.show(recyclerView);
        });

        // ⋮ overflow menu on each song in playlist
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
                        Toast.makeText(this,
                                nowFav ? "Added to favorites" : "Removed from favorites",
                                Toast.LENGTH_SHORT).show();
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
                    .addDestructiveItem(R.drawable.ic_menu_delete, "Delete from Device", () -> {
                        SongActionsHelper.deleteSong(this, song, () -> loadPlaylistSongs());
                    })
                    .show(anchor);
        });

        // Load all songs from device
        allSongs.addAll(MusicRepository.loadAllSongs(this));
        loadPlaylistSongs();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadPlaylistSongs();
    }

    private void loadPlaylistSongs() {
        playlistSongs.clear();

        if (isFavorites) {
            // Load from FavoritesManager
            titleView.setText("★ Favorites");
            FavoritesManager favMgr = new FavoritesManager(this);
            java.util.Set<Long> favIds = favMgr.getFavoriteIds();
            for (long songId : favIds) {
                for (Song s : allSongs) {
                    if (s.getId() == songId) {
                        playlistSongs.add(s);
                        break;
                    }
                }
            }
        } else {
            Playlist playlist = playlistManager.getPlaylist(playlistId);
            if (playlist == null) {
                finish();
                return;
            }
            titleView.setText(playlist.getName());
            // Resolve song IDs to Song objects, preserving playlist order
            for (long songId : playlist.getSongIds()) {
                for (Song s : allSongs) {
                    if (s.getId() == songId) {
                        playlistSongs.add(s);
                        break;
                    }
                }
            }
        }

        int count = playlistSongs.size();
        infoView.setText(count == 1 ? "1 song" : count + " songs");

        adapter.notifyDataSetChanged();
        emptyView.setVisibility(playlistSongs.isEmpty() ? View.VISIBLE : View.GONE);
        recyclerView.setVisibility(playlistSongs.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void exportPlaylistToUri(Uri uri) {
        Playlist playlist = playlistManager.getPlaylist(playlistId);
        if (playlist == null || playlistSongs.isEmpty()) {
            Toast.makeText(this, "No songs to export", Toast.LENGTH_SHORT).show();
            return;
        }

        new Thread(() -> {
            try {
                OutputStream out = getContentResolver().openOutputStream(uri);
                if (out == null) {
                    runOnUiThread(() -> Toast.makeText(this, "❌ Could not write file", Toast.LENGTH_SHORT).show());
                    return;
                }
                boolean success = M3uHelper.exportToStream(this, out, playlist, playlistSongs);
                runOnUiThread(() -> {
                    if (success) {
                        Toast.makeText(this, "✅ Playlist exported as M3U", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "❌ Export failed", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "❌ " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private static String sanitizeFilename(String name) {
        return name.replaceAll("[^a-zA-Z0-9_\\-\\s]", "_").trim();
    }
}
