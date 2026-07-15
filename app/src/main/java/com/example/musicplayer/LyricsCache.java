package com.example.musicplayer;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * File-based cache for lyrics, keyed by artist + title.
 * Cached as JSON in the app's internal cache directory under /lyrics/.
 */
public class LyricsCache {

    private static final String TAG = "LyricsCache";
    private static final String DIR_NAME = "lyrics";

    /**
     * Get cached lyrics for a song, or null if not cached.
     */
    public static List<LyricLine> get(Context context, String artist, String title) {
        File file = getCacheFile(context, artist, title);
        if (!file.exists()) return null;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();

            JSONObject json = new JSONObject(sb.toString());
            JSONArray arr = json.getJSONArray("lines");
            List<LyricLine> lines = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                long ts = obj.getLong("ts");
                String text = obj.getString("t");
                lines.add(new LyricLine(ts, text));
            }
            return lines;
        } catch (Exception e) {
            Log.w(TAG, "Failed to read cache", e);
            file.delete(); // corrupted cache → delete
            return null;
        }
    }

    /**
     * Cache lyrics for a song.
     */
    public static void put(Context context, String artist, String title, List<LyricLine> lines) {
        if (lines == null || lines.isEmpty()) return;

        File file = getCacheFile(context, artist, title);
        try (OutputStreamWriter writer = new OutputStreamWriter(
                new FileOutputStream(file), StandardCharsets.UTF_8)) {
            JSONArray arr = new JSONArray();
            for (LyricLine ll : lines) {
                JSONObject obj = new JSONObject();
                obj.put("ts", ll.timestampMs);
                obj.put("t", ll.text);
                arr.put(obj);
            }
            JSONObject json = new JSONObject();
            json.put("lines", arr);
            writer.write(json.toString());
            writer.flush();
        } catch (Exception e) {
            Log.w(TAG, "Failed to write cache", e);
        }
    }

    private static File getCacheFile(Context context, String artist, String title) {
        File dir = new File(context.getCacheDir(), DIR_NAME);
        if (!dir.exists()) dir.mkdirs();
        String filename = sanitize(artist) + "_" + sanitize(title) + ".json";
        return new File(dir, filename);
    }

    private static String sanitize(String input) {
        if (input == null) return "unknown";
        return input.replaceAll("[^a-zA-Z0-9_\\-]", "_").toLowerCase();
    }
}
