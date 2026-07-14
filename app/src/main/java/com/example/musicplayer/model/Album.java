package com.example.musicplayer.model;

import java.util.ArrayList;
import java.util.List;

public class Album {
    private final long id;
    private final String name;
    private final String artist;
    private final List<Song> songs = new ArrayList<>();

    public Album(long id, String name, String artist) {
        this.id = id;
        this.name = name;
        this.artist = artist;
    }

    public long getId() { return id; }
    public String getName() { return name; }
    public String getArtist() { return artist; }
    public List<Song> getSongs() { return songs; }

    public int getSongCount() { return songs.size(); }

    public long getTotalDuration() {
        long total = 0;
        for (Song s : songs) total += s.getDuration();
        return total;
    }
}
