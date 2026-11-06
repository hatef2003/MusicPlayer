package com.example.musicplayer;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.musicplayer.model.Playlist;
import com.example.musicplayer.model.Song;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Fragment showing all user playlists in a RecyclerView.
 */
public class PlaylistsFragment extends Fragment implements PlaylistManager.OnPlaylistsChangedListener {

    private PlaylistAdapter adapter;
    private final List<Song> allSongs = new ArrayList<>();
    private TextView emptyView;
    private FastScrollerView fastScroller;
    private PlaylistManager playlistManager;

    private final ActivityResultLauncher<String[]> importLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null && getContext() != null) importPlaylistFromUri(uri);
            });

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRetainInstance(true);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_playlists, container, false);
        emptyView = view.findViewById(R.id.emptyText);
        emptyView.setText("No playlists yet\nTap ＋ to create one");

        RecyclerView recyclerView = view.findViewById(R.id.recyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));

        playlistManager = PlaylistManager.getInstance(requireContext());
        playlistManager.setOnPlaylistsChangedListener(this);

        adapter = new PlaylistAdapter(allSongs);
        adapter.setOnPlaylistClickListener(new PlaylistAdapter.OnPlaylistClickListener() {
            @Override
            public void onPlaylistClick(Playlist playlist) {
                Intent intent = new Intent(requireContext(), PlaylistDetailActivity.class);
                intent.putExtra("playlist_id", playlist.getId());
                if (playlist.getId() == -1) {
                    intent.putExtra("is_favorites", true);
                }
                startActivity(intent);
            }

            @Override
            public void onPlaylistLongClick(Playlist playlist) {
                // Don't allow rename/delete on the special Favorites / auto entries
                if (playlist.getId() < 0) return;
                showPlaylistOptionsDialog(playlist);
            }
        });
        recyclerView.setAdapter(adapter);

        // ── Fast scroller ──
        fastScroller = view.findViewById(R.id.fastScroller);
        if (fastScroller != null) {
            fastScroller.setSectionProvider(position -> {
                List<Playlist> playlists = adapter.getPlaylists();
                if (position >= 0 && position < playlists.size()) {
                    String name = playlists.get(position).getName();
                    if (name != null && !name.isEmpty()) {
                        return name.substring(0, 1).toUpperCase();
                    }
                }
                return "";
            });
            fastScroller.attachRecyclerView(recyclerView);
        }

        // Create playlist button
        view.findViewById(R.id.createPlaylistFab).setOnClickListener(v -> showCreatePlaylistDialog());

        // Import M3U button
        view.findViewById(R.id.importPlaylistFab).setOnClickListener(v -> {
            importLauncher.launch(new String[]{"audio/x-mpegurl", "audio/mpegurl"});
        });

        // Apply navigation bar insets to FABs so they're not hidden by OS keys
        View createFab = view.findViewById(R.id.createPlaylistFab);
        View importFab = view.findViewById(R.id.importPlaylistFab);
        float density = getResources().getDisplayMetrics().density;
        int createBaseMargin = (int) (20 * density);
        int importBaseMargin = (int) (84 * density);
        ViewCompat.setOnApplyWindowInsetsListener(createFab, (v, insets) -> {
            int navBarHeight = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
            lp.bottomMargin = createBaseMargin + navBarHeight;
            v.setLayoutParams(lp);
            return insets;
        });
        ViewCompat.setOnApplyWindowInsetsListener(importFab, (v, insets) -> {
            int navBarHeight = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
            lp.bottomMargin = importBaseMargin + navBarHeight;
            v.setLayoutParams(lp);
            return insets;
        });

        loadPlaylists();
        return view;
    }

    public void loadSongs(List<Song> songs) {
        allSongs.clear();
        allSongs.addAll(songs);
        if (!songs.isEmpty() && getContext() != null) {
            // Forget history for tracks that were deleted from the device.
            java.util.Set<Long> ids = new java.util.HashSet<>();
            for (Song s : songs) ids.add(s.getId());
            PlayStatsManager.getInstance(requireContext()).prune(ids);
        }
        if (adapter != null) loadPlaylists();
    }

    private void loadPlaylists() {
        if (getContext() == null) return;
        List<Playlist> playlists = new java.util.ArrayList<>(playlistManager.getAllPlaylists());

        // Prepend a special "★ Favorites" entry (id = -1)
        FavoritesManager favMgr = new FavoritesManager(requireContext());
        int favCount = favMgr.getFavoriteIds().size();
        java.util.List<Long> favIds = new java.util.ArrayList<>(favMgr.getFavoriteIds());
        Playlist favPlaylist = new Playlist(-1, "★ Favorites", favIds, 0);
        playlists.add(0, favPlaylist);

        // Auto-playlists built from listening history
        PlayStatsManager stats = PlayStatsManager.getInstance(requireContext());
        playlists.add(1, new Playlist(PlayStatsManager.MOST_PLAYED_ID, "▲ Most played",
                existingOnly(stats.getMostPlayedIds(PlayStatsManager.AUTO_PLAYLIST_LIMIT)), 0));
        playlists.add(2, new Playlist(PlayStatsManager.RECENTLY_PLAYED_ID, "◷ Recently played",
                existingOnly(stats.getRecentlyPlayedIds(PlayStatsManager.AUTO_PLAYLIST_LIMIT)), 0));

        adapter.setPlaylists(playlists);
        if (fastScroller != null) fastScroller.refresh();
        if (emptyView != null) {
            emptyView.setVisibility(View.GONE); // Always show at least Favorites
        }
    }

    /** Keep only IDs of songs still on the device, so counts match what opens. */
    private List<Long> existingOnly(List<Long> ids) {
        if (allSongs.isEmpty()) return ids; // library not loaded yet
        java.util.Set<Long> present = new java.util.HashSet<>();
        for (Song s : allSongs) present.add(s.getId());
        List<Long> out = new ArrayList<>();
        for (long id : ids) if (present.contains(id)) out.add(id);
        return out;
    }

    @Override
    public void onResume() {
        super.onResume();
        // Play stats change while music plays; refresh the auto-playlist counts.
        if (adapter != null) loadPlaylists();
    }

    @Override
    public void onPlaylistsChanged() {
        if (getActivity() != null) {
            getActivity().runOnUiThread(this::loadPlaylists);
        }
    }

    private void importPlaylistFromUri(Uri uri) {
        if (getContext() == null) return;

        new Thread(() -> {
            try {
                InputStream in = requireContext().getContentResolver().openInputStream(uri);
                if (in == null) {
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(() ->
                                Toast.makeText(getContext(), "❌ Could not read file", Toast.LENGTH_SHORT).show());
                    }
                    return;
                }

                // Read once, parse once
                String[] nameHolder = new String[1];
                List<Song> allDeviceSongs = MusicRepository.loadAllSongs(requireContext());
                List<Song> matchedSongs = M3uHelper.importFromStream(requireContext(), in, allDeviceSongs, nameHolder);
                in.close();

                String playlistName = nameHolder[0];
                if (playlistName == null || playlistName.isEmpty()) {
                    // Use filename as playlist name
                    playlistName = "Imported Playlist";
                    try {
                        String path = uri.getLastPathSegment();
                        if (path != null) {
                            int slash = path.lastIndexOf('/');
                            if (slash >= 0) path = path.substring(slash + 1);
                            int dot = path.lastIndexOf('.');
                            if (dot > 0) path = path.substring(0, dot);
                            if (!path.isEmpty()) playlistName = path;
                        }
                    } catch (Exception ignored) {}
                }

                final String name = playlistName;
                final int count = matchedSongs.size();

                // Build song IDs list
                List<Long> songIds = new ArrayList<>();
                for (Song s : matchedSongs) {
                    songIds.add(s.getId());
                }

                if (songIds.isEmpty()) {
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(() ->
                                Toast.makeText(getContext(), "No matching songs found on device", Toast.LENGTH_LONG).show());
                    }
                    return;
                }

                // Create the playlist
                final List<Long> finalIds = songIds;
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        Playlist newPlaylist = playlistManager.createPlaylist(name);
                        for (long id : finalIds) {
                            playlistManager.addSongToPlaylist(newPlaylist.getId(), id);
                        }
                        Toast.makeText(getContext(),
                                "✅ Imported \"" + name + "\" (" + count + " songs)", Toast.LENGTH_LONG).show();
                    });
                }
            } catch (Exception e) {
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() ->
                            Toast.makeText(getContext(), "❌ Import failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
                }
            }
        }).start();
    }

    private void showCreatePlaylistDialog() {
        if (getContext() == null) return;
        EditText input = new EditText(getContext());
        input.setHint("Playlist name");
        input.setPadding(48, 32, 48, 16);
        input.setTextColor(getResources().getColor(R.color.text_primary));
        input.setHintTextColor(getResources().getColor(R.color.text_secondary));

        new androidx.appcompat.app.AlertDialog.Builder(getContext())
                .setTitle("Create Playlist")
                .setView(input)
                .setPositiveButton("Create", (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (!name.isEmpty()) {
                        playlistManager.createPlaylist(name);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showPlaylistOptionsDialog(Playlist playlist) {
        if (getContext() == null) return;
        String[] options = {"Rename", "Delete"};
        new androidx.appcompat.app.AlertDialog.Builder(getContext())
                .setTitle(playlist.getName())
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        showRenameDialog(playlist);
                    } else {
                        confirmDelete(playlist);
                    }
                })
                .show();
    }

    private void showRenameDialog(Playlist playlist) {
        if (getContext() == null) return;
        EditText input = new EditText(getContext());
        input.setText(playlist.getName());
        input.setPadding(48, 32, 48, 16);
        input.setTextColor(getResources().getColor(R.color.text_primary));

        new androidx.appcompat.app.AlertDialog.Builder(getContext())
                .setTitle("Rename Playlist")
                .setView(input)
                .setPositiveButton("Rename", (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (!name.isEmpty()) {
                        playlistManager.renamePlaylist(playlist.getId(), name);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmDelete(Playlist playlist) {
        if (getContext() == null) return;
        new androidx.appcompat.app.AlertDialog.Builder(getContext())
                .setTitle("Delete Playlist")
                .setMessage("Delete \"" + playlist.getName() + "\"?")
                .setPositiveButton("Delete", (dialog, which) -> {
                    playlistManager.deletePlaylist(playlist.getId());
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (playlistManager != null) {
            playlistManager.setOnPlaylistsChangedListener(null);
        }
    }
}
