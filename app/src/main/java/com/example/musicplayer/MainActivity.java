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
    private SearchView searchView;
    private String currentQuery = "";
    private static final String KEY_SEARCH_QUERY = "search_query";

    // Mini player views
    private LinearLayout miniPlayer;
    private TextView miniTitle;
    private TextView miniArtist;
    private ImageView miniPlayPause;
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
        runOnUiThread(() -> updateMiniPlayer(isPlaying, currentSong, position, duration));
    };

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                Boolean audioGranted = result.get(Manifest.permission.READ_MEDIA_AUDIO);
                Boolean notifGranted = result.get(Manifest.permission.POST_NOTIFICATIONS);
                boolean audioOk = audioGranted != null && audioGranted;
                // On API <= 32 READ_EXTERNAL_STORAGE is used; check it too.
                if (Build.VERSION.SDK_INT <= 32) {
                    audioOk = audioOk || (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED);
                }
                if (audioOk) {
                    loadMusic();
                }
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
        miniSeekBar = findViewById(R.id.miniSeekBar);

        tracksFragment = new TracksFragment();
        albumsFragment = new AlbumsFragment();

        viewPager.setAdapter(new PagerAdapter(this));
        new TabLayoutMediator(tabLayout, viewPager,
                (tab, position) -> tab.setText(position == 0 ? "Tracks" : "Albums")).attach();

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

        checkPermissionsAndLoad();
    }

    private void checkPermissionsAndLoad() {
        boolean audioGranted;
        if (Build.VERSION.SDK_INT >= 33) {
            audioGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED;
        } else {
            audioGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        }
        boolean notifGranted = true;
        if (Build.VERSION.SDK_INT >= 33) {
            notifGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        }

        if (audioGranted) {
            loadMusic();
        } else {
            List<String> perms = new ArrayList<>();
            if (Build.VERSION.SDK_INT >= 33) {
                perms.add(Manifest.permission.READ_MEDIA_AUDIO);
                if (!notifGranted) perms.add(Manifest.permission.POST_NOTIFICATIONS);
            } else {
                perms.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            }
            permissionLauncher.launch(perms.toArray(new String[0]));
        }
    }

    private void loadMusic() {
        if (tracksFragment != null) tracksFragment.loadSongs();
        if (albumsFragment != null) albumsFragment.loadAlbums();
    }

    private void applySearch(String query) {
        currentQuery = query == null ? "" : query;
        if (tracksFragment != null) tracksFragment.setFilterQuery(currentQuery);
        if (albumsFragment != null) albumsFragment.setFilterQuery(currentQuery);
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
        if (duration > 0 && !miniUserDragging) {
            miniSeekBar.setMax(duration);
            miniSeekBar.setProgress(position);
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
            return position == 0 ? tracksFragment : albumsFragment;
        }

        @Override
        public int getItemCount() { return 2; }
    }
}
