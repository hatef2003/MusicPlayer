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
    /** Auto-playlists generated from {@link PlayStatsManager}. */
    private boolean isMostPlayed, isRecentlyPlayed;

    private boolean isAutoPlaylist() { return isMostPlayed || isRecentlyPlayed; }
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
        isMostPlayed = playlistId == PlayStatsManager.MOST_PLAYED_ID;
        isRecentlyPlayed = playlistId == PlayStatsManager.RECENTLY_PLAYED_ID;
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

        if (isAutoPlaylist()) {
            // Show play counts / last-played time on each row
            PlayStatsManager stats = PlayStatsManager.getInstance(this);
            adapter.setSongNoteProvider(song -> {
                PlayStatsManager.Stat s = stats.getStat(song.getId());
                return isMostPlayed
                        ? (s.count == 1 ? "1 play" : s.count + " plays")
                        : formatAgo(s.lastPlayed);
            });
            // The export slot becomes "clear listening history" for auto-playlists
            exportBtn.setText("🗑");
            exportBtn.setContentDescription("Clear listening history");
            exportBtn.setOnClickListener(v -> confirmClearHistory());
        } else exportBtn.setOnClickListener(v -> {
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
            if (!isFavorites && !isAutoPlaylist()) {
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

        // Load all songs from device off the main thread
        MusicRepository.loadAllSongsAsync(this, loaded -> {
            if (isFinishing() || isDestroyed()) return;
            allSongs.clear();
            allSongs.addAll(loaded);
            loadPlaylistSongs();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadPlaylistSongs();
    }

    private void loadPlaylistSongs() {
        playlistSongs.clear();

        if (isAutoPlaylist()) {
            PlayStatsManager stats = PlayStatsManager.getInstance(this);
            titleView.setText(isMostPlayed ? "▲ Most played" : "◷ Recently played");
            List<Long> ids = isMostPlayed
                    ? stats.getMostPlayedIds(PlayStatsManager.AUTO_PLAYLIST_LIMIT)
                    : stats.getRecentlyPlayedIds(PlayStatsManager.AUTO_PLAYLIST_LIMIT);
            java.util.Map<Long, Song> byId = new java.util.HashMap<>();
            for (Song s : allSongs) byId.put(s.getId(), s);
            for (long id : ids) {
                Song s = byId.get(id);
                if (s != null) playlistSongs.add(s);
            }
            emptyView.setText("Nothing here yet\nSongs appear after you listen to them");
        } else if (isFavorites) {
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
        String info = count == 1 ? "1 song" : count + " songs";
        if (isMostPlayed && count > 0) {
            int plays = 0;
            PlayStatsManager stats = PlayStatsManager.getInstance(this);
            for (Song s : playlistSongs) plays += stats.getStat(s.getId()).count;
            info += "  ·  " + plays + (plays == 1 ? " play" : " plays");
        }
        infoView.setText(info);

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

    private void confirmClearHistory() {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Clear listening history")
                .setMessage("Reset play counts and recently played for all songs?")
                .setPositiveButton("Clear", (d, w) -> {
                    PlayStatsManager.getInstance(this).clear();
                    loadPlaylistSongs();
                    Toast.makeText(this, "Listening history cleared", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** "just now", "5m ago", "3h ago", "2d ago", or a date for older plays. */
    static String formatAgo(long timeMs) {
        if (timeMs <= 0) return "";
        long diff = System.currentTimeMillis() - timeMs;
        if (diff < 60_000) return "just now";
        if (diff < 3_600_000) return (diff / 60_000) + "m ago";
        if (diff < 86_400_000) return (diff / 3_600_000) + "h ago";
        if (diff < 7 * 86_400_000L) return (diff / 86_400_000) + "d ago";
        return new java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault())
                .format(new java.util.Date(timeMs));
    }

    private static String sanitizeFilename(String name) {
        return name.replaceAll("[^a-zA-Z0-9_\\-\\s]", "_").trim();
    }
}
