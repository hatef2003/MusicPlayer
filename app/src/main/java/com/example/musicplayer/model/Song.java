package com.example.musicplayer.model;

import android.net.Uri;

import java.util.Locale;

public class Song {
    private final long id;
    private final String title;
    private final String artist;
    private final String album;
    private final long albumId;
    private final long duration;
    private final long dateAdded;
    private final Uri contentUri;

    public Song(long id, String title, String artist, String album, long albumId, long duration, long dateAdded, Uri contentUri) {
        this.id = id;
        this.title = title;
        this.artist = artist;
        this.album = album;
        this.albumId = albumId;
        this.duration = duration;
        this.dateAdded = dateAdded;
        this.contentUri = contentUri;
    }

    public long getId() { return id; }
    public String getTitle() { return title; }
    public String getArtist() { return artist; }
    public String getAlbum() { return album; }
    public long getAlbumId() { return albumId; }
    public long getDuration() { return duration; }
    public long getDateAdded() { return dateAdded; }
    public Uri getContentUri() { return contentUri; }

    public String getDurationLabel() {
        long seconds = duration / 1000;
        long m = seconds / 60;
        long s = seconds % 60;
        return String.format(Locale.US, "%d:%02d", m, s);
    }
}
