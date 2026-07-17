package com.example.musicplayer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.AudioManager;

import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import androidx.media.app.NotificationCompat.MediaStyle;


import com.bumptech.glide.Glide;
import com.example.musicplayer.model.Song;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MusicService extends Service implements MediaPlayer.OnCompletionListener {

    private static final String TAG = "MusicService";
    private static final String CHANNEL_ID = "music_playback";
    private static final int NOTIFICATION_ID = 1;

    public static final String ACTION_PAUSE = "com.example.musicplayer.PAUSE";
    public static final String ACTION_RESUME = "com.example.musicplayer.RESUME";
    public static final String ACTION_NEXT = "com.example.musicplayer.NEXT";
    public static final String ACTION_PREV = "com.example.musicplayer.PREV";
    public static final String ACTION_STOP = "com.example.musicplayer.STOP";

    public enum RepeatMode { OFF, ONE, ALL }

    private final IBinder binder = new MusicBinder();
    private MediaPlayer mediaPlayer;
    private List<Song> queue = new ArrayList<>();
    private List<Song> originalQueue = new ArrayList<>();
    private int currentIndex = -1;
    private boolean isPrepared = false;
    private boolean shuffleOn = false;
    private RepeatMode repeatMode = RepeatMode.OFF;
    private final Random random = new Random();
    private MediaSessionCompat mediaSession;
    private PlaybackListener listener;
    private volatile Bitmap currentArtBitmap;

    private final ExecutorService artExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface PlaybackListener {
        void onStateChanged(boolean isPlaying, Song currentSong, int position, int duration);
    }

    public class MusicBinder extends Binder {
        public MusicService getService() { return MusicService.this; }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        mediaSession = new MediaSessionCompat(this, "MusicPlayerSession");
        mediaSession.setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS |
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override public void onPlay() { resume(); }
            @Override public void onPause() { pause(); }
            @Override public void onSkipToNext() { next(); }
            @Override public void onSkipToPrevious() { prev(); }
            @Override public void onStop() { pause(); }
            @Override public void onSeekTo(long pos) { seekTo((int) pos); }
        });
        mediaSession.setActive(true);

        mediaPlayer = new MediaPlayer();
        mediaPlayer.setOnCompletionListener(this);
        mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
    }

    public void setPlaybackListener(PlaybackListener listener) {
        this.listener = listener;
    }

    public void setQueue(List<Song> songs, int startIndex) {
        this.originalQueue = new ArrayList<>(songs);
        if (shuffleOn) {
            Song current = songs.get(startIndex);
            this.queue = new ArrayList<>(songs);
            Collections.shuffle(queue, random);
            this.currentIndex = queue.indexOf(current);
            if (this.currentIndex < 0) this.currentIndex = 0;
        } else {
            this.queue = new ArrayList<>(songs);
            this.currentIndex = startIndex;
        }
        playCurrent();
    }

    public void playCurrent() {
        if (queue.isEmpty() || currentIndex < 0 || currentIndex >= queue.size()) return;
        Song song = queue.get(currentIndex);
        try {
            isPrepared = false;
            mediaPlayer.reset();
            mediaPlayer.setDataSource(getApplicationContext(), song.getContentUri());
            mediaPlayer.prepare();
            isPrepared = true;
            mediaPlayer.start();
            currentArtBitmap = null;

            updateMediaMetadata(song);
            startForegroundCompat(buildNotification(true));
            notifyStateChanged(true);
            loadAlbumArtAsync(song);
        } catch (Exception e) {
            Log.e(TAG, "playCurrent failed", e);
        }
    }

    public void pause() {
        if (isPrepared && mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
            stopForeground(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(false));
            notifyStateChanged(false);
        }
    }

    public void resume() {
        if (isPrepared && !mediaPlayer.isPlaying()) {
            mediaPlayer.start();
            startForegroundCompat(buildNotification(true));
            notifyStateChanged(true);
        }
    }

    public void next() {
        if (repeatMode == RepeatMode.ONE) {
            playCurrent();
        } else if (currentIndex < queue.size() - 1) {
            currentIndex++;
            playCurrent();
        } else if (repeatMode == RepeatMode.ALL) {
            currentIndex = 0;
            playCurrent();
        }
    }

    public void prev() {
        if (repeatMode == RepeatMode.ONE) {
            playCurrent();
        } else if (currentIndex > 0) {
            currentIndex--;
            playCurrent();
        } else if (repeatMode == RepeatMode.ALL) {
            currentIndex = queue.size() - 1;
            playCurrent();
        }
    }

    public void toggleShuffle() {
        shuffleOn = !shuffleOn;
        if (queue.isEmpty()) return;
        Song currentSong = getCurrentSong();
        if (shuffleOn) {
            // Shuffle: randomize queue but keep current song at current index
            List<Song> remaining = new ArrayList<>();
            for (int i = 0; i < queue.size(); i++) {
                if (i != currentIndex) remaining.add(queue.get(i));
            }
            Collections.shuffle(remaining, random);
            List<Song> shuffled = new ArrayList<>();
            for (int i = 0; i < queue.size(); i++) {
                if (i == currentIndex) {
                    shuffled.add(currentSong);
                } else {
                    shuffled.add(remaining.remove(0));
                }
            }
            this.queue = shuffled;
        } else {
            // Un-shuffle: restore original order
            int idx = originalQueue.indexOf(currentSong);
            this.queue = new ArrayList<>(originalQueue);
            this.currentIndex = idx >= 0 ? idx : 0;
        }
    }

    public void toggleRepeat() {
        switch (repeatMode) {
            case OFF: repeatMode = RepeatMode.ALL; break;
            case ALL: repeatMode = RepeatMode.ONE; break;
            case ONE: repeatMode = RepeatMode.OFF; break;
        }
    }

    public boolean isShuffleOn() { return shuffleOn; }
    public RepeatMode getRepeatMode() { return repeatMode; }

    public void seekTo(int msec) {
        if (isPrepared) mediaPlayer.seekTo(msec);
    }

    public boolean isPlaying() {
        return isPrepared && mediaPlayer.isPlaying();
    }

    public Song getCurrentSong() {
        if (currentIndex >= 0 && currentIndex < queue.size()) return queue.get(currentIndex);
        return null;
    }

    public int getPosition() { return isPrepared ? mediaPlayer.getCurrentPosition() : 0; }
    public int getDuration() { return isPrepared ? mediaPlayer.getDuration() : 0; }
    public int getCurrentIndex() { return currentIndex; }
    public int getQueueSize() { return queue.size(); }

    public List<Song> getQueue() {
        return new ArrayList<>(queue);
    }

    public void playFromQueue(int index) {
        if (index >= 0 && index < queue.size()) {
            currentIndex = index;
            playCurrent();
        }
    }

    /**
     * Replace the queue without restarting playback.
     * Use this after drag-to-reorder so the current song keeps playing.
     */
    public void setQueueSilent(List<Song> songs, int newCurrentIndex) {
        this.queue = new ArrayList<>(songs);
        this.currentIndex = newCurrentIndex;
        if (!shuffleOn) {
            this.originalQueue = new ArrayList<>(songs);
        }
    }

    /**
     * Remove a song from the queue by position.
     * Adjusts currentIndex if the removed song was before or at the current.
     */
    public void removeFromQueue(int position) {
        if (position < 0 || position >= queue.size()) return;
        boolean wasPlaying = isPlaying();
        boolean wasCurrent = (position == currentIndex);
        queue.remove(position);
        if (wasCurrent) {
            // Current song was removed — play next, or stop if queue is empty
            if (queue.isEmpty()) {
                currentIndex = -1;
                if (mediaPlayer != null) { mediaPlayer.reset(); isPrepared = false; }
                stopForeground(true);
                notifyStateChanged(false);
            } else {
                if (currentIndex >= queue.size()) currentIndex = 0;
                playCurrent();
            }
        } else if (position < currentIndex) {
            currentIndex--;
        }
    }

    private void startNotifProgressUpdates() {
        // MediaStyle handles progress display via PlaybackState
    }

    private void stopNotifProgressUpdates() {
        // MediaStyle handles progress display via PlaybackState
    }

    private void notifyStateChanged(boolean playing) {
        if (listener != null) {
            Song s = getCurrentSong();
            listener.onStateChanged(playing, s, getPosition(), getDuration());
        }
        updatePlaybackState(playing);
        mediaSession.setActive(true);
        if (playing) {
            startNotifProgressUpdates();
        } else {
            stopNotifProgressUpdates();
        }
    }

    private void updatePlaybackState(boolean playing) {
        int state = playing ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED;
        long actions = PlaybackStateCompat.ACTION_PLAY
                | PlaybackStateCompat.ACTION_PAUSE
                | PlaybackStateCompat.ACTION_PLAY_PAUSE
                | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                | PlaybackStateCompat.ACTION_SEEK_TO
                | PlaybackStateCompat.ACTION_STOP;
        PlaybackStateCompat playbackState = new PlaybackStateCompat.Builder()
                .setState(state, getPosition(), 1.0f)
                .setActions(actions)
                .build();
        mediaSession.setPlaybackState(playbackState);
    }

    @Override
    public void onCompletion(MediaPlayer mp) {
        if (repeatMode == RepeatMode.ONE) {
            playCurrent();
        } else if (currentIndex < queue.size() - 1) {
            next();
        } else if (repeatMode == RepeatMode.ALL) {
            currentIndex = 0;
            playCurrent();
        } else {
            // RepeatMode.OFF and end of queue
            stopForeground(true);
            notifyStateChanged(false);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            String action = intent.getAction();
            switch (action) {
                case ACTION_PAUSE: pause(); break;
                case ACTION_RESUME: resume(); break;
                case ACTION_NEXT: next(); break;
                case ACTION_PREV: prev(); break;
                case ACTION_STOP:
                    stopForeground(true);
                    stopSelf();
                    break;
                case Intent.ACTION_MEDIA_BUTTON:
                    // Forward media button events to the session callback
                    android.view.KeyEvent keyEvent = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                    if (keyEvent != null) {
                        mediaSession.getController().dispatchMediaButtonEvent(keyEvent);
                    }
                    break;
            }
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        stopNotifProgressUpdates();
        artExecutor.shutdownNow();
        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }
        if (mediaSession != null) mediaSession.release();
        super.onDestroy();
    }

    private void updateMediaMetadata(Song song) {
        MediaMetadataCompat.Builder mb = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, song.getTitle())
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, song.getArtist())
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, song.getAlbum())
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, song.getDuration());
        if (currentArtBitmap != null) {
            mb.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, currentArtBitmap);
        }
        mediaSession.setMetadata(mb.build());
    }

    private void loadAlbumArtAsync(Song song) {
        artExecutor.execute(() -> {
            // Load album art
            Bitmap bitmap = null;
            try {
                bitmap = Glide.with(this)
                        .asBitmap()
                        .load(MusicRepository.getAlbumArtUri(song.getAlbumId()))
                        .submit(256, 256)
                        .get();
            } catch (Exception e) {
                Log.d(TAG, "No album art for: " + song.getTitle());
            }
            currentArtBitmap = bitmap;

            // Single notification refresh with both format + art
            updateMediaMetadata(song);
            mainHandler.post(() -> {
                NotificationManager nm = getSystemService(NotificationManager.class);
                if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(isPlaying()));
            });
        });
    }

    private void startForegroundCompat(Notification notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Music Player", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Playback controls and now playing");
            channel.setShowBadge(false);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(boolean isPlaying) {
        Song song = getCurrentSong();
        String title = song != null ? song.getTitle() : "Music Player";
        String artist = song != null ? song.getArtist() : "";
        String album = song != null ? song.getAlbum() : "";

        // Build PendingIntents for controls
        Intent contentIntent = new Intent(this, NowPlayingActivity.class);
        contentIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentPi = PendingIntent.getActivity(this, 0, contentIntent,
                PendingIntent.FLAG_IMMUTABLE);

        String playPauseAction = isPlaying ? ACTION_PAUSE : ACTION_RESUME;
        Intent pauseIntent = new Intent(this, MusicService.class).setAction(playPauseAction);
        PendingIntent pausePi = PendingIntent.getService(this, 1, pauseIntent,
                PendingIntent.FLAG_IMMUTABLE);
        Intent nextIntent = new Intent(this, MusicService.class).setAction(ACTION_NEXT);
        PendingIntent nextPi = PendingIntent.getService(this, 2, nextIntent,
                PendingIntent.FLAG_IMMUTABLE);
        Intent prevIntent = new Intent(this, MusicService.class).setAction(ACTION_PREV);
        PendingIntent prevPi = PendingIntent.getService(this, 3, prevIntent,
                PendingIntent.FLAG_IMMUTABLE);

        // ── MediaStyle notification with system media controls ──
        int playPauseIcon = isPlaying ? R.drawable.ic_notif_pause_green : R.drawable.ic_notif_play_green;

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notif_small)
                .setContentTitle(title)
                .setContentText(artist)
                .setSubText(album)
                .setContentIntent(contentPi)
                .setShowWhen(false)
                .setOngoing(isPlaying)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
                .setColor(Color.parseColor("#101010"))
                .setColorized(true)
                // Actions: prev, play/pause, next
                .addAction(R.drawable.ic_notif_prev_green, "Previous", prevPi)
                .addAction(playPauseIcon, isPlaying ? "Pause" : "Play", pausePi)
                .addAction(R.drawable.ic_notif_next_green, "Next", nextPi)
                // MediaStyle: connects to MediaSession for system controls
                .setStyle(new MediaStyle()
                        .setMediaSession(mediaSession.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2))
                .setLargeIcon(currentArtBitmap);

        return builder.build();
    }


}
