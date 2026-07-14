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
}
