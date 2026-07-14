package com.example.musicplayer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Binder;
import android.os.Build;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.media.app.NotificationCompat.MediaStyle;

import com.bumptech.glide.Glide;
import com.example.musicplayer.model.Song;

import java.util.ArrayList;
import java.util.List;
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

    private final IBinder binder = new MusicBinder();
    private MediaPlayer mediaPlayer;
    private List<Song> queue = new ArrayList<>();
    private int currentIndex = -1;
    private boolean isPrepared = false;
    private MediaSessionCompat mediaSession;
    private PlaybackListener listener;
    private Bitmap currentArtBitmap;
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
        mediaPlayer = new MediaPlayer();
        mediaPlayer.setOnCompletionListener(this);
        mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
    }

    public void setPlaybackListener(PlaybackListener listener) {
        this.listener = listener;
    }

    public void setQueue(List<Song> songs, int startIndex) {
        this.queue = new ArrayList<>(songs);
        this.currentIndex = startIndex;
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
        if (currentIndex < queue.size() - 1) {
            currentIndex++;
            playCurrent();
        }
    }

    public void prev() {
        if (currentIndex > 0) {
            currentIndex--;
            playCurrent();
        }
    }

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

    private void notifyStateChanged(boolean playing) {
        if (listener != null) {
            Song s = getCurrentSong();
            listener.onStateChanged(playing, s, getPosition(), getDuration());
        }
        mediaSession.setActive(playing);
    }

    @Override
    public void onCompletion(MediaPlayer mp) {
        if (currentIndex < queue.size() - 1) {
            next();
        } else {
            // End of queue
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
            }
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
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
            try {
                Bitmap bitmap = Glide.with(this)
                        .asBitmap()
                        .load(MusicRepository.getAlbumArtUri(song.getAlbumId()))
                        .submit(256, 256)
                        .get();
                currentArtBitmap = bitmap;
                updateMediaMetadata(song);
                // Refresh the notification with the artwork on the main thread
                mainHandler.post(() -> {
                    NotificationManager nm = getSystemService(NotificationManager.class);
                    if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(isPlaying()));
                });
            } catch (Exception e) {
                Log.d(TAG, "No album art for: " + song.getTitle());
            }
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
                    CHANNEL_ID, "Music Playback", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Music playback controls");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(boolean isPlaying) {
        Song song = getCurrentSong();
        String title = song != null ? song.getTitle() : "Music Player";
        String artist = song != null ? song.getArtist() : "";
        int playPauseIcon = isPlaying ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play;
        String playPauseText = isPlaying ? "Pause" : "Play";
        String playPauseAction = isPlaying ? ACTION_PAUSE : ACTION_RESUME;

        Intent contentIntent = new Intent(this, NowPlayingActivity.class);
        contentIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentPi = PendingIntent.getActivity(this, 0, contentIntent,
                PendingIntent.FLAG_IMMUTABLE);

        Intent pauseIntent = new Intent(this, MusicService.class).setAction(playPauseAction);
        PendingIntent pausePi = PendingIntent.getService(this, 1, pauseIntent,
                PendingIntent.FLAG_IMMUTABLE);

        Intent nextIntent = new Intent(this, MusicService.class).setAction(ACTION_NEXT);
        PendingIntent nextPi = PendingIntent.getService(this, 2, nextIntent,
                PendingIntent.FLAG_IMMUTABLE);

        Intent prevIntent = new Intent(this, MusicService.class).setAction(ACTION_PREV);
        PendingIntent prevPi = PendingIntent.getService(this, 3, prevIntent,
                PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(title)
                .setContentText(artist)
                .setContentIntent(contentPi)
                .setShowWhen(false)
                .setOngoing(isPlaying)
                .addAction(android.R.drawable.ic_media_previous, "Previous", prevPi)
                .addAction(playPauseIcon, playPauseText, pausePi)
                .addAction(android.R.drawable.ic_media_next, "Next", nextPi)
                .setStyle(new MediaStyle()
                        .setMediaSession(mediaSession.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2));

        if (currentArtBitmap != null) {
            builder.setLargeIcon(currentArtBitmap);
        }

        return builder.build();
    }
}
