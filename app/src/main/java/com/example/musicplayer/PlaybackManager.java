package com.example.musicplayer;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;

import com.example.musicplayer.model.Song;

import java.util.ArrayList;
import java.util.List;

public class PlaybackManager {

    public interface Callback {
        void onPlaybackChanged(boolean isPlaying, Song currentSong, int position, int duration);
    }

    private static PlaybackManager instance;
    private MusicService musicService;
    private boolean isBound = false;
    private final List<Callback> callbacks = new ArrayList<>();
    private final Context appContext;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            MusicService.MusicBinder binder = (MusicService.MusicBinder) service;
            musicService = binder.getService();
            musicService.setPlaybackListener((isPlaying, currentSong, position, duration) -> {
                for (Callback c : callbacks) {
                    c.onPlaybackChanged(isPlaying, currentSong, position, duration);
                }
            });
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            musicService = null;
        }
    };

    private PlaybackManager(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public static synchronized PlaybackManager getInstance(Context context) {
        if (instance == null) {
            instance = new PlaybackManager(context);
        }
        return instance;
    }

    public void bind() {
        if (isBound) return;
        Intent intent = new Intent(appContext, MusicService.class);
        appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE);
        appContext.startService(intent);
        isBound = true;
    }

    public void unbind() {
        if (isBound) {
            appContext.unbindService(connection);
            isBound = false;
        }
    }

    public void addCallback(Callback callback) {
        callbacks.add(callback);
        if (musicService != null) {
            callback.onPlaybackChanged(musicService.isPlaying(),
                    musicService.getCurrentSong(),
                    musicService.getPosition(),
                    musicService.getDuration());
        }
    }

    public void removeCallback(Callback callback) {
        callbacks.remove(callback);
    }

    public void play(List<Song> songs, int index) {
        if (musicService != null) {
            musicService.setQueue(songs, index);
        }
    }

    public void pause() { if (musicService != null) musicService.pause(); }
    public void resume() { if (musicService != null) musicService.resume(); }
    public void next() { if (musicService != null) musicService.next(); }
    public void prev() { if (musicService != null) musicService.prev(); }
    public void seekTo(int msec) { if (musicService != null) musicService.seekTo(msec); }

    public boolean isPlaying() { return musicService != null && musicService.isPlaying(); }
    public Song getCurrentSong() { return musicService != null ? musicService.getCurrentSong() : null; }
    public int getPosition() { return musicService != null ? musicService.getPosition() : 0; }
    public int getDuration() { return musicService != null ? musicService.getDuration() : 0; }
    public int getQueueSize() { return musicService != null ? musicService.getQueueSize() : 0; }
    public int getCurrentIndex() { return musicService != null ? musicService.getCurrentIndex() : 0; }

    public List<Song> getQueue() {
        if (musicService != null) return musicService.getQueue();
        return new ArrayList<>();
    }

    public void playFromQueue(int index) {
        if (musicService != null) musicService.playFromQueue(index);
    }

    public void setQueueSilent(List<Song> songs, int newCurrentIndex) {
        if (musicService != null) musicService.setQueueSilent(songs, newCurrentIndex);
    }

    public void removeFromQueue(int position) {
        if (musicService != null) musicService.removeFromQueue(position);
    }

    public void toggleShuffle() {
        if (musicService != null) musicService.toggleShuffle();
    }

    public void toggleRepeat() {
        if (musicService != null) musicService.toggleRepeat();
    }

    public boolean isShuffleOn() {
        return musicService != null && musicService.isShuffleOn();
    }

    public MusicService.RepeatMode getRepeatMode() {
        return musicService != null ? musicService.getRepeatMode() : MusicService.RepeatMode.OFF;
    }

    /**
     * Reload songs from MediaStore and update the queue entry for the current song.
     * Call this after metadata (title/artist/album) has been updated externally.
     */
    public void refreshCurrentSong(Context context) {
        if (musicService == null) return;
        Song current = musicService.getCurrentSong();
        if (current == null) return;
        // Reload all songs and find the one with the same ID
        List<Song> refreshed = MusicRepository.loadAllSongs(context);
        for (Song s : refreshed) {
            if (s.getId() == current.getId()) {
                // Replace in the queue
                List<Song> queue = musicService.getQueue();
                int idx = musicService.getCurrentIndex();
                if (idx >= 0 && idx < queue.size()) {
                    queue.set(idx, s);
                    musicService.setQueueSilent(queue, idx);
                }
                break;
            }
        }
    }
}
