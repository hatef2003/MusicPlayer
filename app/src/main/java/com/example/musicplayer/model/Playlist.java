package com.example.musicplayer.model;

import java.util.ArrayList;
import java.util.List;

public class Playlist {
    private final long id;
    private String name;
    private final List<Long> songIds;
    private final long dateCreated;

    public Playlist(long id, String name, List<Long> songIds, long dateCreated) {
        this.id = id;
        this.name = name;
        this.songIds = songIds != null ? new ArrayList<>(songIds) : new ArrayList<>();
        this.dateCreated = dateCreated;
    }

    public long getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public List<Long> getSongIds() { return songIds; }
    public long getDateCreated() { return dateCreated; }

    public int getSongCount() { return songIds.size(); }

    public boolean containsSong(long songId) {
        return songIds.contains(songId);
    }

    public void addSong(long songId) {
        if (!songIds.contains(songId)) {
            songIds.add(songId);
        }
    }

    public void removeSong(long songId) {
        songIds.remove(songId);
    }
}
