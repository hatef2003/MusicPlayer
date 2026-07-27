package com.example.musicplayer;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.widget.SearchView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.example.musicplayer.model.Song;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends BaseActivity {

    private ViewPager2 viewPager;
    private TabLayout tabLayout;
    private TracksFragment tracksFragment;
    private AlbumsFragment albumsFragment;
    private PlaylistsFragment playlistsFragment;
    private SearchView searchView;
    private String currentQuery = "";
    private static final String KEY_SEARCH_QUERY = "search_query";

    // Mini player views
    private LinearLayout miniPlayer;
    private TextView miniTitle;
    private TextView miniArtist;
    private ImageView miniPlayPause;
    private ImageView miniAlbumArt;
    private SeekBar miniSeekBar;
    private boolean miniUserDragging = false;

    private final Handler miniHandler = new Handler(Looper.getMainLooper());
    private final Runnable miniProgressUpdater = new Runnable() {
        @Override
        public void run() {
            PlaybackManager pm = PlaybackManager.getInstance(MainActivity.this);
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

    private final PlaybackManager.Callback callback = (isPlaying, currentSong, position, duration) -> {
        runOnUiThread(() -> {
            updateMiniPlayer(isPlaying, currentSong, position, duration);
            // Update playing-song highlight in tracks list
            if (tracksFragment != null && currentSong != null) {
                tracksFragment.updatePlayingSong(currentSong.getId());
            }
        });
    };

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                // Permission state is observed in onResume, which reloads music when granted.
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        viewPager = findViewById(R.id.viewPager);
        tabLayout = findViewById(R.id.tabLayout);
        searchView = findViewById(R.id.searchView);
        miniPlayer = findViewById(R.id.miniPlayer);
        miniTitle = findViewById(R.id.miniTitle);
        miniArtist = findViewById(R.id.miniArtist);
        miniPlayPause = findViewById(R.id.miniPlayPause);
        miniAlbumArt = findViewById(R.id.miniAlbumArt);
        miniSeekBar = findViewById(R.id.miniSeekBar);

        tracksFragment = new TracksFragment();
        albumsFragment = new AlbumsFragment();
        playlistsFragment = new PlaylistsFragment();

        viewPager.setAdapter(new PagerAdapter(this));
        viewPager.setOffscreenPageLimit(2);
        new TabLayoutMediator(tabLayout, viewPager,
                (tab, position) -> {
                    switch (position) {
                        case 0: tab.setText("Tracks"); break;
                        case 1: tab.setText("Albums"); break;
                        case 2: tab.setText("Playlists"); break;
                    }
                }).attach();

        ImageView settingsButton = findViewById(R.id.settingsButton);
        settingsButton.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));

        searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                applySearch(query);
                return true;
            }

            @Override
            public boolean onQueryTextChange(String newText) {
                applySearch(newText);
                return true;
            }
        });

        if (savedInstanceState != null) {
            currentQuery = savedInstanceState.getString(KEY_SEARCH_QUERY, "");
            searchView.setQuery(currentQuery, false);
            applySearch(currentQuery);
        }

        miniPlayPause.setOnClickListener(v -> {
            PlaybackManager pm = PlaybackManager.getInstance(this);
            if (pm.isPlaying()) pm.pause();
            else pm.resume();
        });

        miniPlayer.setOnClickListener(v -> {
            if (PlaybackManager.getInstance(this).getCurrentSong() != null) {
                startActivity(new Intent(this, NowPlayingActivity.class));
            }
        });

        miniSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {}
            @Override public void onStartTrackingTouch(SeekBar sb) {
                miniUserDragging = true;
            }
            @Override public void onStopTrackingTouch(SeekBar sb) {
                miniUserDragging = false;
                PlaybackManager.getInstance(MainActivity.this).seekTo(sb.getProgress());
            }
        });

        // Apply status bar inset to the custom header so it's not covered
        applyStatusBarInset(findViewById(R.id.header));

        // Apply navigation bar inset to the mini player so it's not hidden
        applyNavigationBarInset(findViewById(R.id.miniPlayer));

        checkPermissions();

        // Handle "Go to Artist" intent from context menu
        handleIncomingIntent();

        // ── Tab-aware back navigation ──
        // 1st back: if search is active → clear search
        // 2nd back: if not on Tracks tab → go to Tracks tab
        // 3rd back: exit app
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                // If search is active, clear it first
                if (!currentQuery.isEmpty()) {
                    searchView.setQuery("", false);
                    applySearch("");
                    return;
                }
                // If not on the Tracks tab, go to Tracks tab
                if (viewPager.getCurrentItem() != 0) {
                    viewPager.setCurrentItem(0, true);
                    return;
                }
                // We're on Tracks with no search — allow default behavior (exit app)
                setEnabled(false);
                getOnBackPressedDispatcher().onBackPressed();
            }
        });
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent();
    }

    /** If the intent carries a filter_artist extra, apply it as a search filter. */
    private void handleIncomingIntent() {
        Intent intent = getIntent();
        if (intent != null && intent.hasExtra("filter_artist")) {
            String artist = intent.getStringExtra("filter_artist");
            intent.removeExtra("filter_artist"); // consume it once
            if (artist != null && !artist.isEmpty()) {
                // Delay slightly so the tabs/fragments are ready
                viewPager.post(() -> searchByArtist(artist));
            }
        }
    }

    /**
     * Request audio permission if not already granted.
     * Music loading is handled in {@link #onResume()} once permission is granted.
     */
    private void checkPermissions() {
        if (isAudioPermissionGranted()) return;

        boolean notifGranted = true;
        if (Build.VERSION.SDK_INT >= 33) {
            notifGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        }

        List<String> perms = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33) {
            perms.add(Manifest.permission.READ_MEDIA_AUDIO);
            if (!notifGranted) perms.add(Manifest.permission.POST_NOTIFICATIONS);
        } else {
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        }
        permissionLauncher.launch(perms.toArray(new String[0]));
    }

    private boolean isAudioPermissionGranted() {
        if (Build.VERSION.SDK_INT >= 33) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED;
        } else {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        }
    }

    private void loadMusic() {
        MusicRepository.loadAllSongsAsync(this, allSongs -> {
            if (isFinishing() || isDestroyed()) return;
            if (tracksFragment != null) tracksFragment.loadSongs(allSongs);
            if (albumsFragment != null) albumsFragment.loadAlbums(allSongs);
            if (playlistsFragment != null) playlistsFragment.loadSongs(allSongs);
        });
    }

    private void applySearch(String query) {
        currentQuery = query == null ? "" : query;
        if (tracksFragment != null) tracksFragment.setFilterQuery(currentQuery);
        if (albumsFragment != null) albumsFragment.setFilterQuery(currentQuery);
    }

    /** Called from AlbumsFragment when user taps "Go to Artist" in album card menu. */
    public void searchByArtist(String artist) {
        if (artist == null || artist.isEmpty()) return;
        // Set the search query and switch to Tracks tab
        searchView.setQuery(artist, false);
        applySearch(artist);
        viewPager.setCurrentItem(0, true);
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(KEY_SEARCH_QUERY, currentQuery);
    }

    private void updateMiniPlayer(boolean isPlaying, Song currentSong, int position, int duration) {
        if (currentSong == null) {
            miniPlayer.setVisibility(View.GONE);
            return;
        }
        miniPlayer.setVisibility(View.VISIBLE);
        miniTitle.setText(currentSong.getTitle());
        miniArtist.setText(currentSong.getArtist());
        miniPlayPause.setImageResource(isPlaying ? R.drawable.ic_pause : R.drawable.ic_play);
        com.bumptech.glide.Glide.with(this)
                .load(MusicRepository.getAlbumArtUri(currentSong.getAlbumId()))
                .placeholder(R.drawable.ic_album).error(R.drawable.ic_album)
                .centerCrop().into(miniAlbumArt);
        if (duration > 0 && !miniUserDragging) {
            miniSeekBar.setMax(duration);
            miniSeekBar.setProgress(position);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Refresh music data every time the activity returns so metadata changes
        // (e.g. AudD, tag editor) are reflected in the library.
        if (isAudioPermissionGranted()) {
            loadMusic();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        PlaybackManager pm = PlaybackManager.getInstance(this);
        pm.bind();
        pm.addCallback(callback);
        miniHandler.post(miniProgressUpdater);
    }

    @Override
    protected void onStop() {
        super.onStop();
        miniHandler.removeCallbacks(miniProgressUpdater);
        PlaybackManager pm = PlaybackManager.getInstance(this);
        pm.removeCallback(callback);
    }

    private class PagerAdapter extends FragmentStateAdapter {
        PagerAdapter(@NonNull FragmentActivity fa) { super(fa); }

        @NonNull
        @Override
        public Fragment createFragment(int position) {
            switch (position) {
                case 0: return tracksFragment;
                case 1: return albumsFragment;
                default: return playlistsFragment;
            }
        }

        @Override
        public int getItemCount() { return 3; }
    }
}
