package com.example.musicplayer;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;

import com.example.musicplayer.model.Album;
import com.example.musicplayer.model.Song;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MusicRepository {

    public static List<Song> loadAllSongs(Context context) {
        List<Song> songs = new ArrayList<>();
        String[] projection = new String[]{
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DATE_ADDED
        };
        String selection = MediaStore.Audio.Media.IS_MUSIC + " != 0";
        String sortOrder = MediaStore.Audio.Media.TITLE + " ASC";

        try (Cursor cursor = context.getContentResolver().query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection, selection, null, sortOrder)) {
            if (cursor == null) return songs;
            int idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
            int titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
            int artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
            int albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM);
            int albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID);
            int durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
            int dateAddedCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED);

            while (cursor.moveToNext()) {
                long id = cursor.getLong(idCol);
                String title = cursor.getString(titleCol);
                String artist = cursor.getString(artistCol);
                String album = cursor.getString(albumCol);
                long albumId = cursor.getLong(albumIdCol);
                long duration = cursor.getLong(durationCol);
                long dateAdded = cursor.getLong(dateAddedCol);
                Uri contentUri = ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id);
                songs.add(new Song(id, title, artist, album, albumId, duration, dateAdded, contentUri));
            }
        }
        return songs;
    }

    public static List<Album> loadAlbums(Context context) {
        List<Song> songs = loadAllSongs(context);
        // Preserve insertion order; first artist seen wins.
        Map<Long, Album> albumMap = new LinkedHashMap<>();
        for (Song s : songs) {
            Album album = albumMap.get(s.getAlbumId());
            if (album == null) {
                album = new Album(s.getAlbumId(), s.getAlbum(), s.getArtist());
                albumMap.put(s.getAlbumId(), album);
            }
            album.getSongs().add(s);
        }
        return new ArrayList<>(albumMap.values());
    }

    public static Uri getAlbumArtUri(long albumId) {
        return ContentUris.withAppendedId(
                Uri.parse("content://media/external/audio/albumart"), albumId);
    }
}
