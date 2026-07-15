package com.example.musicplayer;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.util.Log;

import com.example.musicplayer.model.Song;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches lyrics from online APIs and falls back to embedded metadata.
 */
public class LyricsService {

    private static final String TAG = "LyricsService";
    private static final String LRCLIB_API = "https://lrclib.net/api/get";

    /** Single-thread executor for embedding tasks so we don't spawn unlimited threads. */
    private static final ExecutorService embedExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "LyricsEmbedder");
        t.setDaemon(true);
        return t;
    });

    /** Regex: [mm:ss.xx] or [mm:ss.xxx] */
    private static final Pattern LRC_PATTERN =
            Pattern.compile("\\[(\\d{2}):(\\d{2})\\.(\\d{2,3})\\]");

    public interface LyricsCallback {
        /** Called with parsed lyric lines. Lines may be synced (with timestamps) or plain. */
        void onLyricsLoaded(List<LyricLine> lines);
        void onError(String message);
    }

    /**
     * Fetch lyrics for a song. Checks cache first, then API, then embedded metadata.
     */
    public static void fetchLyrics(Context context, Song song, LyricsCallback callback) {
        new Thread(() -> {
            // 1. Check cache first
            List<LyricLine> cached = LyricsCache.get(context, song.getArtist(), song.getTitle());
            if (cached != null && !cached.isEmpty()) {
                callback.onLyricsLoaded(cached);
                return;
            }

            // 2. Try online lyrics API
            List<LyricLine> lines = fetchFromApi(song.getTitle(), song.getArtist());
            if (lines != null && !lines.isEmpty()) {
                LyricsCache.put(context, song.getArtist(), song.getTitle(), lines);
                embedInBackground(context, song, lines);
                callback.onLyricsLoaded(lines);
                return;
            }

            // 3. Try embedded lyrics in audio file (don't cache — may be low quality)
            String embedded = extractEmbeddedLyrics(context, song.getContentUri());
            if (embedded != null && !embedded.isEmpty()) {
                callback.onLyricsLoaded(parseLyrics(embedded));
                return;
            }

            // 4. Not found
            callback.onError("No lyrics found for this song");
        }).start();
    }

    /**
     * Fetch lyrics skipping the cache — always hits the network.
     */
    public static void fetchFresh(Context context, Song song, LyricsCallback callback) {
        new Thread(() -> {
            List<LyricLine> lines = fetchFromApi(song.getTitle(), song.getArtist());
            if (lines != null && !lines.isEmpty()) {
                LyricsCache.put(context, song.getArtist(), song.getTitle(), lines);
                embedInBackground(context, song, lines);
                callback.onLyricsLoaded(lines);
                return;
            }
            String embedded = extractEmbeddedLyrics(context, song.getContentUri());
            if (embedded != null && !embedded.isEmpty()) {
                callback.onLyricsLoaded(parseLyrics(embedded));
                return;
            }
            callback.onError("No lyrics found");
        }).start();
    }

    /**
     * Fetch lyrics from lrclib.net API. Returns synced LRC lines if available,
     * otherwise plain text lines.
     */
    private static List<LyricLine> fetchFromApi(String title, String artist) {
        try {
            String query = String.format("?artist_name=%s&track_name=%s",
                    URLEncoder.encode(artist, "UTF-8"),
                    URLEncoder.encode(title, "UTF-8"));
            URL url = new URL(LRCLIB_API + query);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("User-Agent", "AndroidMusicPlayer/1.0");

            int code = conn.getResponseCode();
            if (code != 200) {
                conn.disconnect();
                return null;
            }

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();
            conn.disconnect();

            JSONObject json = new JSONObject(sb.toString());

            // Prefer synced lyrics
            String synced = json.optString("syncedLyrics", null);
            if (synced != null && !synced.isEmpty() && !synced.equals("null")) {
                List<LyricLine> lines = parseLRC(synced);
                if (!lines.isEmpty()) return lines;
            }

            // Fall back to plain lyrics
            String plain = json.optString("plainLyrics", null);
            if (plain != null && !plain.isEmpty() && !plain.equals("null")) {
                return parseLyrics(plain);
            }

            return null;
        } catch (Exception e) {
            Log.e(TAG, "API fetch failed", e);
            return null;
        }
    }

    /**
     * Parse LRC-format lyrics into timestamped LyricLine objects.
     * Lines without timestamps (or metadata tags) are skipped or treated as continuation.
     */
    private static List<LyricLine> parseLRC(String lrc) {
        List<LyricLine> lines = new ArrayList<>();
        Matcher m = LRC_PATTERN.matcher("");
        for (String raw : lrc.split("\n")) {
            String trimmed = raw.trim();
            if (trimmed.isEmpty()) continue;

            m.reset(trimmed);
            if (m.find()) {
                // Collect all timestamps from this line
                List<Long> timestamps = new ArrayList<>();
                int lastEnd = 0;
                m.reset(trimmed);
                while (m.find()) {
                    int min = Integer.parseInt(m.group(1));
                    int sec = Integer.parseInt(m.group(2));
                    int centi = Integer.parseInt(m.group(3));
                    long ms = (min * 60L + sec) * 1000L;
                    if (m.group(3).length() == 2) {
                        ms += centi * 10L;
                    } else {
                        ms += centi;
                    }
                    timestamps.add(ms);
                    lastEnd = m.end();
                }

                String text = trimmed.substring(lastEnd).trim();
                if (!text.isEmpty()) {
                    for (long ts : timestamps) {
                        lines.add(new LyricLine(ts, text));
                    }
                }
            }
        }

        lines.sort((a, b) -> Long.compare(a.timestampMs, b.timestampMs));
        return lines;
    }

    /**
     * Parse plain text into un-timestamped LyricLine objects.
     */
    private static List<LyricLine> parseLyrics(String plain) {
        List<LyricLine> lines = new ArrayList<>();
        for (String raw : plain.split("\n")) {
            String trimmed = raw.trim();
            if (!trimmed.isEmpty()) {
                lines.add(new LyricLine(-1, trimmed));
            }
        }
        return lines;
    }

    /** Join lines and embed into the music file on a single background thread. */
    private static void embedInBackground(Context context, Song song, List<LyricLine> lines) {
        embedExecutor.execute(() -> {
            StringBuilder sb = new StringBuilder();
            for (LyricLine ll : lines) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(ll.text);
            }
            LyricsEmbedder.EmbedResult result = LyricsEmbedder.embedLyrics(context,
                    song.getContentUri(), song.getArtist(), song.getTitle(), sb.toString());
            if (result.success) {
                Log.i(TAG, result.message + ": " + song.getTitle());
            } else {
                Log.w(TAG, result.message + ": " + song.getTitle());
            }
        });
    }

    /**
     * Extract embedded lyrics from audio file using MediaMetadataRetriever.
     */
    private static String extractEmbeddedLyrics(Context context, Uri contentUri) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, contentUri);
            String lyrics = retriever.extractMetadata(19);
            if (lyrics == null || lyrics.isEmpty()) {
                lyrics = retriever.extractMetadata(20);
            }
            return lyrics;
        } catch (Exception e) {
            Log.d(TAG, "No embedded lyrics found");
            return null;
        } finally {
            try { retriever.release(); } catch (Exception ignored) {}
        }
    }
}
