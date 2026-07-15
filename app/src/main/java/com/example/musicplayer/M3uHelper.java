package com.example.musicplayer;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Log;

import com.example.musicplayer.model.Playlist;
import com.example.musicplayer.model.Song;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Utility for exporting/importing playlists in M3U format.
 *
 * M3U format:
 *   #EXTM3U
 *   #PLAYLIST:Playlist Name
 *   #EXTINF:duration_seconds,Artist - Title
 *   /storage/emulated/0/Music/song.mp3
 */
public class M3uHelper {

    private static final String TAG = "M3uHelper";

    /**
     * Write a playlist as M3U to the given output stream.
     * Uses file paths from MediaStore for maximum compatibility.
     */
    public static boolean exportToStream(Context context, OutputStream out, Playlist playlist, List<Song> songs) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("#EXTM3U\n");
            sb.append("#PLAYLIST:").append(playlist.getName()).append("\n");

            for (Song song : songs) {
                long durationSec = song.getDuration() / 1000;
                sb.append("#EXTINF:").append(durationSec).append(",")
                  .append(song.getArtist()).append(" - ").append(song.getTitle()).append("\n");

                // Try to get the file path for maximum compatibility
                String filePath = resolveFilePath(context, song.getId());
                if (filePath != null) {
                    sb.append(filePath).append("\n");
                } else {
                    // Fallback: use content URI
                    sb.append(song.getContentUri().toString()).append("\n");
                }
            }

            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.close();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Export failed", e);
            return false;
        }
    }

    /**
     * Read an entire InputStream into a byte array.
     */
    private static byte[] readAllBytes(InputStream in) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] tmp = new byte[4096];
        int len;
        while ((len = in.read(tmp)) != -1) {
            buffer.write(tmp, 0, len);
        }
        in.close();
        return buffer.toByteArray();
    }

    /**
     * Read an M3U file from the given input stream and try to match songs
     * against the device library. Returns a list of matched Song objects.
     * Also populates the playlist name into the provided holder if present.
     */
    public static List<Song> importFromStream(Context context, InputStream in, List<Song> allSongs,
                                               String[] playlistNameHolder) {
        List<Song> matched = new ArrayList<>();
        try {
            // Read all bytes once so we can parse multiple times from memory
            byte[] data = readAllBytes(in);

            // Extract playlist name
            try (BufferedReader nameReader = new BufferedReader(
                    new InputStreamReader(new ByteArrayInputStream(data), StandardCharsets.UTF_8))) {
                String line;
                while ((line = nameReader.readLine()) != null) {
                    line = line.trim();
                    if (line.startsWith("#PLAYLIST:")) {
                        if (playlistNameHolder != null && playlistNameHolder.length > 0) {
                            playlistNameHolder[0] = line.substring("#PLAYLIST:".length()).trim();
                        }
                        break;
                    }
                    if (!line.startsWith("#") && !line.isEmpty()) break;
                }
            }

            // Parse the full file
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new ByteArrayInputStream(data), StandardCharsets.UTF_8))) {
                String line;
                String pendingTitle = null;
                String pendingArtist = null;

                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#PLAYLIST:")) continue;

                    if (line.startsWith("#EXTINF:")) {
                        // Parse: #EXTINF:duration,Artist - Title
                        int commaIdx = line.indexOf(',');
                        if (commaIdx > 0) {
                            String afterComma = line.substring(commaIdx + 1).trim();
                            int dashIdx = afterComma.indexOf(" - ");
                            if (dashIdx > 0) {
                                pendingArtist = afterComma.substring(0, dashIdx).trim();
                                pendingTitle = afterComma.substring(dashIdx + 3).trim();
                            } else {
                                pendingTitle = afterComma;
                                pendingArtist = null;
                            }
                        }
                    } else if (line.startsWith("#")) {
                        continue; // Skip other comments
                    } else {
                        // This is a file path or URI line — try to match
                        Song match = findMatchingSong(pendingTitle, pendingArtist, allSongs);
                        if (match != null) {
                            matched.add(match);
                        }
                        pendingTitle = null;
                        pendingArtist = null;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Import failed", e);
        }
        return matched;
    }

    /**
     * Try to match metadata to a song in the library.
     * Strategy: 1) Match by title+artist, 2) Match by title only, 3) Partial title match.
     */
    private static Song findMatchingSong(String title, String artist, List<Song> allSongs) {
        if (title == null) return null;

        String titleLower = title.toLowerCase(Locale.ROOT);
        String artistLower = artist != null ? artist.toLowerCase(Locale.ROOT) : null;

        // Strategy 1: Exact title + artist match
        if (artistLower != null) {
            for (Song s : allSongs) {
                if (s.getTitle().toLowerCase(Locale.ROOT).equals(titleLower)
                        && s.getArtist().toLowerCase(Locale.ROOT).equals(artistLower)) {
                    return s;
                }
            }
        }

        // Strategy 2: Title-only exact match
        for (Song s : allSongs) {
            if (s.getTitle().toLowerCase(Locale.ROOT).equals(titleLower)) {
                return s;
            }
        }

        // Strategy 3: Partial title match
        for (Song s : allSongs) {
            if (s.getTitle().toLowerCase(Locale.ROOT).contains(titleLower)
                    || titleLower.contains(s.getTitle().toLowerCase(Locale.ROOT))) {
                return s;
            }
        }

        return null;
    }

    /**
     * Resolve the file path for a song by its MediaStore ID.
     */
    private static String resolveFilePath(Context context, long songId) {
        try (Cursor cursor = context.getContentResolver().query(
                ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, songId),
                new String[]{MediaStore.Audio.Media.DATA},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getString(0);
            }
        } catch (Exception e) {
            Log.d(TAG, "Could not resolve file path for song " + songId);
        }
        return null;
    }
}
