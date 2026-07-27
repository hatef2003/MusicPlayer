package com.example.musicplayer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
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
import com.example.musicplayer.widget.MusicWidgetProvider;

import org.json.JSONArray;
import org.json.JSONObject;

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

    // ── Playback state persistence (for restart after system kill) ──
    private static final String STATE_PREFS = "music_playback_state";
    private static final long STATE_SAVE_INTERVAL_MS = 10_000;
    private final Runnable stateSaveRunnable = new Runnable() {
        @Override public void run() {
            savePlaybackState();
            mainHandler.postDelayed(this, STATE_SAVE_INTERVAL_MS);
        }
    };
    private boolean periodicSaveRunning = false;

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
    private volatile boolean isPreparing = false;
    private int pendingSeekOnPrepared = 0;
    private boolean playOnPrepare = true;
    private long prepareGeneration = 0;
    private volatile long currentPrepareGeneration = 0;
    private boolean shuffleOn = false;
    private RepeatMode repeatMode = RepeatMode.OFF;
    private final Random random = new Random();
    private MediaSessionCompat mediaSession;
    private PlaybackListener listener;
    private volatile Bitmap currentArtBitmap;

    private final ExecutorService artExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final long WIDGET_PROGRESS_INTERVAL_MS = 1_000;
    private final Runnable widgetProgressRunnable = new Runnable() {
        @Override public void run() {
            if (isPlaying()) {
                MusicWidgetProvider.updateProgress(MusicService.this, getPosition());
                mainHandler.postDelayed(this, WIDGET_PROGRESS_INTERVAL_MS);
            }
        }
    };

    // Audio focus + becoming-noisy handling
    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest; // API 26+
    private AudioManager.OnAudioFocusChangeListener focusListener;
    private boolean pausedByFocusLoss = false;
    private boolean ducked = false;
    private boolean hasAudioFocus = false;
    private final BroadcastReceiver becomingNoisyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
                // Respect the user's setting: when disabled, keep playing on the
                // new output (e.g. phone speaker) instead of pausing.
                if (new SettingsManager(context).shouldPauseOnBecomingNoisy()) {
                    pause();
                }
            }
        }
    };

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
            @Override public void onPlay() {
                // If the queue is empty, the service was likely killed and
                // restarted by a media-button / Bluetooth / system media control
                // press. Restore the last saved queue + position before resuming.
                if (queue.isEmpty()) {
                    restorePlaybackState(MusicService.this::resume);
                } else {
                    resume();
                }
            }
            @Override public void onPause() { pause(); }
            @Override public void onSkipToNext() {
                if (queue.isEmpty()) {
                    restorePlaybackState(MusicService.this::next);
                } else {
                    next();
                }
            }
            @Override public void onSkipToPrevious() {
                if (queue.isEmpty()) {
                    restorePlaybackState(MusicService.this::prev);
                } else {
                    prev();
                }
            }
            @Override public void onStop() { pause(); }
            @Override public void onSeekTo(long pos) { seekTo((int) pos); }
        });
        mediaSession.setActive(true);

        mediaPlayer = new MediaPlayer();
        mediaPlayer.setOnCompletionListener(this);
        mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);

        // Audio focus + becoming-noisy
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        focusListener = this::onAudioFocusChange;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            registerReceiver(becomingNoisyReceiver,
                    new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                    Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(becomingNoisyReceiver,
                    new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
        }
    }

    public void setPlaybackListener(PlaybackListener listener) {
        this.listener = listener;
    }

    public void setQueue(List<Song> songs, int startIndex) {
        if (songs == null || songs.isEmpty()) return;
        if (startIndex < 0) startIndex = 0;
        if (startIndex >= songs.size()) startIndex = songs.size() - 1;

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
        if (!requestAudioFocus()) {
            Log.w(TAG, "Could not gain audio focus - not playing");
            return;
        }
        Song song = queue.get(currentIndex);
        try {
            // Cancel any ongoing async preparation before starting a new track.
            if (isPreparing) {
                mediaPlayer.reset();
            }
            isPrepared = false;
            isPreparing = true;
            playOnPrepare = true;
            currentArtBitmap = null;
            long generation = ++prepareGeneration;
            currentPrepareGeneration = generation;

            mediaPlayer.reset();
            mediaPlayer.setOnPreparedListener(mp -> onPrepared(generation));
            mediaPlayer.setOnErrorListener((mp, what, extra) -> onPlayerError(generation, what, extra));
            mediaPlayer.setDataSource(getApplicationContext(), song.getContentUri());
            mediaPlayer.prepareAsync();

            updateMediaMetadata(song);
            loadAlbumArtAsync(song, generation);
        } catch (Exception e) {
            Log.e(TAG, "playCurrent failed", e);
            isPreparing = false;
            isPrepared = false;
        }
    }

    /** Called when {@link MediaPlayer} finishes async preparation. */
    private void onPrepared(long generation) {
        if (generation != currentPrepareGeneration) {
            // Stale callback from a cancelled/previous preparation — ignore it.
            return;
        }
        isPrepared = true;
        isPreparing = false;

        if (pendingSeekOnPrepared > 0) {
            mediaPlayer.seekTo(pendingSeekOnPrepared);
            pendingSeekOnPrepared = 0;
        }

        if (playOnPrepare) {
            mediaPlayer.start();
            startForegroundCompat(buildNotification(true));
            notifyStateChanged(true);
        } else {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(false));
            notifyStateChanged(false);
        }
    }

    /** Called when {@link MediaPlayer} encounters an error. */
    private boolean onPlayerError(long generation, int what, int extra) {
        if (generation != currentPrepareGeneration) {
            // Stale error from a cancelled/previous preparation — ignore it.
            return true;
        }
        Log.e(TAG, "MediaPlayer error: what=" + what + " extra=" + extra);
        isPrepared = false;
        isPreparing = false;
        mediaPlayer.reset();
        notifyStateChanged(false);
        return true;
    }

    public void pause() {
        if (isPreparing) {
            playOnPrepare = false;
            notifyStateChanged(false);
        } else if (isPrepared && mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
            stopForeground(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(false));
            notifyStateChanged(false);
        }
    }

    public void resume() {
        if (isPreparing) {
            playOnPrepare = true;
            notifyStateChanged(true);
        } else if (isPrepared && !mediaPlayer.isPlaying()) {
            if (!requestAudioFocus()) {
                Log.w(TAG, "Could not regain audio focus");
                return;
            }
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
        if (isPreparing) {
            pendingSeekOnPrepared = msec;
        } else if (isPrepared) {
            mediaPlayer.seekTo(msec);
        }
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
        // Also start the widget progress timer for real-time updates.
        mainHandler.removeCallbacks(widgetProgressRunnable);
        mainHandler.post(widgetProgressRunnable);
    }

    private void stopNotifProgressUpdates() {
        // MediaStyle handles progress display via PlaybackState
        mainHandler.removeCallbacks(widgetProgressRunnable);
    }

    // ═══ PLAYBACK STATE PERSISTENCE (restart after system kill) ═══

    private void startPeriodicStateSave() {
        if (periodicSaveRunning) return;
        periodicSaveRunning = true;
        mainHandler.postDelayed(stateSaveRunnable, STATE_SAVE_INTERVAL_MS);
    }

    private void stopPeriodicStateSave() {
        if (!periodicSaveRunning) return;
        periodicSaveRunning = false;
        mainHandler.removeCallbacks(stateSaveRunnable);
    }

    /**
     * Persist the current queue (as a list of MediaStore audio IDs), the current
     * index, the playback position, and shuffle/repeat state, to SharedPreferences
     * as JSON. Called periodically while playing, on pause, and on destroy so the
     * service can be rebuilt if the system kills it.
     */
    private void savePlaybackState() {
        if (queue.isEmpty() || currentIndex < 0) {
            // Nothing valid to save — leave any existing state intact so we don't
            // clobber a previously saved queue right before a kill.
            return;
        }
        try {
            JSONArray idArray = new JSONArray();
            for (Song s : queue) idArray.put(s.getId());
            JSONObject state = new JSONObject();
            state.put("ids", idArray);
            state.put("currentIndex", currentIndex);
            state.put("position", getPosition());
            state.put("shuffle", shuffleOn);
            state.put("repeat", repeatMode.name());
            getSharedPreferences(STATE_PREFS, MODE_PRIVATE)
                    .edit()
                    .putString("state", state.toString())
                    .apply();
        } catch (Exception e) {
            Log.w(TAG, "savePlaybackState failed", e);
        }
    }

    /**
     * Rebuild the queue from the last saved state, fetch fresh {@link Song}
     * objects from the MediaStore by ID, and start playback at the saved
     * position. Runs asynchronously to avoid blocking the main thread.
     *
     * <p>Called from the MediaSession {@code onPlay()} callback and the
     * {@link #ACTION_RESUME}/{@link #ACTION_NEXT}/{@link #ACTION_PREV} intents
     * when the queue is empty — i.e. the service was killed and restarted by a
     * media-button / Bluetooth / system media control press.</p>
     */
    private void restorePlaybackState(Runnable onRestored) {
        String json = getSharedPreferences(STATE_PREFS, MODE_PRIVATE)
                .getString("state", null);
        if (json == null) {
            if (onRestored != null) onRestored.run();
            return;
        }
        MusicRepository.loadAllSongsAsync(this, catalog -> {
            try {
                JSONObject state = new JSONObject(json);
                JSONArray idArray = state.getJSONArray("ids");
                int savedIndex = state.optInt("currentIndex", -1);
                int savedPos = state.optInt("position", 0);
                boolean savedShuffle = state.optBoolean("shuffle", false);
                String repeatName = state.optString("repeat", RepeatMode.OFF.name());

                // Look up each saved ID in the current MediaStore catalog and rebuild
                // the queue with fresh Song objects (content URIs, metadata, etc.).
                // Songs that no longer exist are skipped (index adjusted below).
                List<Song> restored = new ArrayList<>();
                int newCurrentIndex = -1;
                long targetSongId = -1L;
                if (savedIndex >= 0 && savedIndex < idArray.length()) {
                    targetSongId = idArray.getLong(savedIndex);
                }
                for (int i = 0; i < idArray.length(); i++) {
                    long id = idArray.getLong(i);
                    for (Song s : catalog) {
                        if (s.getId() == id) {
                            restored.add(s);
                            if (id == targetSongId) newCurrentIndex = restored.size() - 1;
                            break;
                        }
                    }
                }
                if (restored.isEmpty() || newCurrentIndex < 0) {
                    Log.w(TAG, "restorePlaybackState: saved queue no longer resolvable");
                    clearPlaybackState();
                    if (onRestored != null) onRestored.run();
                    return;
                }
                this.queue = restored;
                this.originalQueue = new ArrayList<>(restored);
                this.currentIndex = newCurrentIndex;
                this.shuffleOn = savedShuffle;
                try { this.repeatMode = RepeatMode.valueOf(repeatName); }
                catch (Exception ignored) { this.repeatMode = RepeatMode.OFF; }

                // Start playback; deferred seek is handled by pendingSeekOnPrepared
                // once the async prepare completes.
                if (savedPos > 0) {
                    pendingSeekOnPrepared = savedPos;
                }
                playCurrent();
                Log.i(TAG, "restorePlaybackState: restored " + restored.size()
                        + " tracks at index " + newCurrentIndex + ", pos " + savedPos);
            } catch (Exception e) {
                Log.w(TAG, "restorePlaybackState failed", e);
                clearPlaybackState();
            } finally {
                if (onRestored != null) onRestored.run();
            }
        });
    }

    /**
     * Remove any saved playback state. Called when the user explicitly stops
     * playback or when the queue finishes naturally, so we don't auto-resume a
     * stale queue the next time the service is started.
     */
    private void clearPlaybackState() {
        getSharedPreferences(STATE_PREFS, MODE_PRIVATE)
                .edit()
                .remove("state")
                .apply();
    }

    private void notifyStateChanged(boolean playing) {
        if (listener != null) {
            Song s = getCurrentSong();
            listener.onStateChanged(playing, s, getPosition(), getDuration());
        }
        updatePlaybackState(playing);
        mediaSession.setActive(true);
        // Push state to home screen widget
        Song cs = getCurrentSong();
        MusicWidgetProvider.updateAllWidgets(this, playing,
                cs != null ? cs.getTitle() : null,
                cs != null ? cs.getArtist() : null,
                currentArtBitmap, getPosition(), getDuration());
        if (playing) {
            startNotifProgressUpdates();
            startPeriodicStateSave();
        } else {
            stopNotifProgressUpdates();
            stopPeriodicStateSave();
            // Save the paused position so we can resume after a kill.
            savePlaybackState();
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
            clearPlaybackState();
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
                case ACTION_RESUME:
                    // Notification play button / system media control. If the
                    // queue is empty, the service was killed and restarted —
                    // restore the last saved queue + position first.
                    if (queue.isEmpty()) {
                        restorePlaybackState(MusicService.this::resume);
                    } else {
                        resume();
                    }
                    break;
                case ACTION_NEXT:
                    if (queue.isEmpty()) {
                        restorePlaybackState(MusicService.this::next);
                    } else {
                        next();
                    }
                    break;
                case ACTION_PREV:
                    if (queue.isEmpty()) {
                        restorePlaybackState(MusicService.this::prev);
                    } else {
                        prev();
                    }
                    break;
                case ACTION_STOP:
                    clearPlaybackState();
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
        stopPeriodicStateSave();
        // Persist the last position so we can resume if the system kills us.
        savePlaybackState();
        try {
            unregisterReceiver(becomingNoisyReceiver);
        } catch (Exception ignored) {}
        abandonAudioFocus();
        artExecutor.shutdownNow();
        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }
        if (mediaSession != null) mediaSession.release();
        super.onDestroy();
    }

    // ═══ AUDIO FOCUS + BECOMING NOISY ═══

    /**
     * Handle audio focus changes.
     *
     * Two modes controlled by the "pause on focus loss" setting:
     *
     * Setting ON (full focus handling):
     * - LOSS_TRANSIENT_CAN_DUCK: lower volume (ringing / notifications).
     * - LOSS_TRANSIENT: pause + auto-resume on gain (call answered).
     * - LOSS: pause without auto-resume (another app took over permanently).
     * - GAIN: restore volume, resume if paused by a transient loss.
     *
     * Setting OFF (keep playing through ringing, but still pause for calls):
     * - LOSS_TRANSIENT_CAN_DUCK: keep playing at full volume (phone ringing).
     * - LOSS_TRANSIENT: pause + auto-resume on gain (call was answered).
     * - LOSS: pause + auto-resume on gain (call active / focus taken; if focus
     *         is returned after the call, music resumes).
     * - GAIN: resume if we paused for a call.
     */
    private void onAudioFocusChange(int focusChange) {
        // Audio focus callbacks may be delivered on a binder/worker thread, but
        // MediaPlayer, MediaSession, and the playback listener (which updates
        // the NowPlayingActivity UI) must only be touched from the main thread.
        // Hop to the main thread before doing anything — otherwise enabling the
        // "pause on focus loss" setting crashes the app when a call comes in,
        // because pause()/resume()/setVolume() reach MediaPlayer off-thread and
        // notifyStateChanged() drives view updates off-thread.
        mainHandler.post(() -> handleAudioFocusChange(focusChange));
    }

    private void handleAudioFocusChange(int focusChange) {
        if (mediaPlayer == null) return; // service destroyed — ignore stale callback
        // Respect the user's setting: when disabled, ignore focus changes entirely
        // (the player keeps playing through calls / other apps taking focus).
        // Headphone disconnect is controlled by a separate setting.
        boolean handleFocus = new SettingsManager(this).shouldPauseOnFocusLoss();
        if (!handleFocus) {
            // Setting disabled: keep playing through ringing / notifications,
            // but still pause when a call is actually answered so the user can
            // take the call, and auto-resume when focus is returned afterwards.
            switch (focusChange) {
                case AudioManager.AUDIOFOCUS_LOSS:
                    // Focus taken permanently (e.g. call active, or another app
                    // took over). Pause. We still set pausedByFocusLoss so that
                    // if focus is returned after a call ends, music resumes —
                    // if it's truly permanent, GAIN never arrives so the flag is
                    // harmless.
                    pausedByFocusLoss = isPlaying();
                    hasAudioFocus = false;
                    pause();
                    break;
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                    // Call answered — pause and remember to auto-resume on GAIN.
                    pausedByFocusLoss = isPlaying();
                    hasAudioFocus = false;
                    pause();
                    break;
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                    // Phone ringing / notification — keep playing at full volume.
                    // (Flag is off: user wants music to continue through ringing.)
                    break;
                case AudioManager.AUDIOFOCUS_GAIN:
                    hasAudioFocus = true;
                    if (ducked) {
                        ducked = false;
                        if (isPrepared && mediaPlayer != null) mediaPlayer.setVolume(1f, 1f);
                    }
                    if (pausedByFocusLoss) {
                        pausedByFocusLoss = false;
                        resume();
                    }
                    break;
            }
            return;
        }
        switch (focusChange) {
            case AudioManager.AUDIOFOCUS_LOSS:
                pausedByFocusLoss = false;
                hasAudioFocus = false;
                if (ducked) {
                    ducked = false;
                    if (isPrepared && mediaPlayer != null) mediaPlayer.setVolume(1f, 1f);
                }
                pause();
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                // We've lost focus (e.g. call answered/placed). Pause and remember
                // to auto-resume on GAIN — but only if we were actually playing,
                // so a user's manual pause is respected across a call.
                pausedByFocusLoss = isPlaying();
                hasAudioFocus = false;
                pause();
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                if (isPlaying() && !ducked) {
                    ducked = true;
                    if (isPrepared && mediaPlayer != null) mediaPlayer.setVolume(0.2f, 0.2f);
                }
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                // Focus has been granted back to us.
                hasAudioFocus = true;
                if (ducked) {
                    ducked = false;
                    if (isPrepared && mediaPlayer != null) mediaPlayer.setVolume(1f, 1f);
                }
                if (pausedByFocusLoss) {
                    pausedByFocusLoss = false;
                    resume();
                }
                break;
        }
    }

    private boolean requestAudioFocus() {
        if (audioManager == null) return false;
        // Already have focus — no need to re-request (avoids redundant IPC when
        // resume() is called from the AUDIOFOCUS_GAIN handler).
        if (hasAudioFocus) return true;
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attributes)
                    .setOnAudioFocusChangeListener(focusListener)
                    .setWillPauseWhenDucked(false)
                    .build();
            int result = audioManager.requestAudioFocus(audioFocusRequest);
            hasAudioFocus = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
            return hasAudioFocus;
        } else {
            int result = audioManager.requestAudioFocus(focusListener,
                    AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
            hasAudioFocus = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
            return hasAudioFocus;
        }
    }

    private void abandonAudioFocus() {
        if (audioManager == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioFocusRequest != null) {
            audioManager.abandonAudioFocusRequest(audioFocusRequest);
            audioFocusRequest = null;
        } else {
            audioManager.abandonAudioFocus(focusListener);
        }
        hasAudioFocus = false;
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

    private void loadAlbumArtAsync(Song song, long generation) {
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

            final Bitmap artBitmap = bitmap;
            mainHandler.post(() -> {
                // Ignore stale art loads from a previous/preparation generation.
                if (generation != currentPrepareGeneration) return;
                currentArtBitmap = artBitmap;
                // Refresh MediaSession metadata with the loaded art, then rebuild notification.
                updateMediaMetadata(song);
                NotificationManager nm = getSystemService(NotificationManager.class);
                if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(isPlaying()));
                // Push updated album art to widget
                Song cs = getCurrentSong();
                MusicWidgetProvider.updateAllWidgets(MusicService.this, isPlaying(),
                        cs != null ? cs.getTitle() : null,
                        cs != null ? cs.getArtist() : null,
                        artBitmap, getPosition(), getDuration());
            });
        });
    }

    private void startForegroundCompat(Notification notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (Exception e) {
            // Android 12+ (API 31) blocks background apps from (re)starting a
            // foreground service. This happens when resume() is triggered by
            // AUDIOFOCUS_GAIN (e.g. after a call ends) while the app is in the
            // background — pause() already called stopForeground(false), so
            // re-entering foreground from a background context is refused.
            // Fall back to a regular notification so playback still resumes and
            // the user sees the media controls, without crashing.
            // NOTE: mediaPlayer.start() runs BEFORE this call in resume()/
            // playCurrent(), so the music is already playing by the time we get
            // here — this catch only protects the notification/foreground status.
            Log.w(TAG, "startForeground not allowed (background FGS restriction) — posting notification instead", e);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.notify(NOTIFICATION_ID, notification);
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
