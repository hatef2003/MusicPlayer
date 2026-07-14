package com.example.musicplayer;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.mediarouter.app.MediaRouteButton;
import androidx.mediarouter.media.MediaControlIntent;
import androidx.mediarouter.media.MediaRouteSelector;

import com.bumptech.glide.Glide;
import com.example.musicplayer.model.Song;

import java.util.Locale;

public class NowPlayingActivity extends BaseActivity {

    private TextView titleView;
    private TextView artistView;
    private TextView albumView;
    private TextView currentTime;
    private TextView totalTime;
    private SeekBar seekBar;
    private ImageView playPauseBtn;
    private ImageView prevBtn;
    private ImageView nextBtn;
    private ImageView albumArtView;
    private MediaRouteButton mediaRouteButton;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean userDragging = false;
    private final Runnable updateRunnable = new Runnable() {
        @Override
        public void run() {
            PlaybackManager pm = PlaybackManager.getInstance(NowPlayingActivity.this);
            if (pm.isPlaying() && !userDragging) {
                int pos = pm.getPosition();
                int dur = pm.getDuration();
                if (dur > 0) {
                    seekBar.setMax(dur);
                    seekBar.setProgress(pos);
                    currentTime.setText(formatTime(pos));
                    totalTime.setText(formatTime(dur));
                }
            }
            handler.postDelayed(this, 500);
        }
    };

    private final PlaybackManager.Callback callback = (isPlaying, currentSong, position, duration) -> {
        runOnUiThread(() -> {
            if (currentSong != null) {
                titleView.setText(currentSong.getTitle());
                artistView.setText(currentSong.getArtist());
                albumView.setText(currentSong.getAlbum());
                playPauseBtn.setImageResource(isPlaying ? R.drawable.ic_pause : R.drawable.ic_play);
                loadAlbumArt(currentSong);
                if (duration > 0 && !userDragging) {
                    seekBar.setMax(duration);
                    seekBar.setProgress(position);
                    currentTime.setText(formatTime(position));
                    totalTime.setText(formatTime(duration));
                }
            }
        });
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_now_playing);

        titleView = findViewById(R.id.npTitle);
        artistView = findViewById(R.id.npArtist);
        albumView = findViewById(R.id.npAlbum);
        currentTime = findViewById(R.id.npCurrentTime);
        totalTime = findViewById(R.id.npTotalTime);
        seekBar = findViewById(R.id.npSeekBar);
        playPauseBtn = findViewById(R.id.npPlayPause);
        prevBtn = findViewById(R.id.npPrev);
        nextBtn = findViewById(R.id.npNext);
        albumArtView = findViewById(R.id.npAlbumArt);
        mediaRouteButton = findViewById(R.id.mediaRouteButton);

        MediaRouteSelector selector = new MediaRouteSelector.Builder()
                .addControlCategory(MediaControlIntent.CATEGORY_LIVE_AUDIO)
                .addControlCategory(MediaControlIntent.CATEGORY_LIVE_VIDEO)
                .build();
        mediaRouteButton.setRouteSelector(selector);

        PlaybackManager pm = PlaybackManager.getInstance(this);

        playPauseBtn.setOnClickListener(v -> {
            if (pm.isPlaying()) pm.pause();
            else pm.resume();
        });

        prevBtn.setOnClickListener(v -> pm.prev());
        nextBtn.setOnClickListener(v -> pm.next());

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser) {
                    currentTime.setText(formatTime(progress));
                }
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {
                userDragging = true;
            }
            @Override public void onStopTrackingTouch(SeekBar sb) {
                userDragging = false;
                pm.seekTo(sb.getProgress());
            }
        });

        // Initialize with current state
        Song current = pm.getCurrentSong();
        if (current != null) {
            titleView.setText(current.getTitle());
            artistView.setText(current.getArtist());
            albumView.setText(current.getAlbum());
            playPauseBtn.setImageResource(pm.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);
            loadAlbumArt(current);
        }
    }

    private void loadAlbumArt(Song song) {
        Glide.with(this)
                .load(MusicRepository.getAlbumArtUri(song.getAlbumId()))
                .placeholder(R.drawable.ic_album)
                .error(R.drawable.ic_album)
                .centerCrop()
                .into(albumArtView);
    }

    private String formatTime(int msec) {
        int seconds = msec / 1000;
        int m = seconds / 60;
        int s = seconds % 60;
        return String.format(Locale.US, "%d:%02d", m, s);
    }

    @Override
    protected void onStart() {
        super.onStart();
        PlaybackManager pm = PlaybackManager.getInstance(this);
        pm.bind();
        pm.addCallback(callback);
        handler.post(updateRunnable);
    }

    @Override
    protected void onStop() {
        super.onStop();
        handler.removeCallbacks(updateRunnable);
        PlaybackManager.getInstance(this).removeCallback(callback);
    }
}
