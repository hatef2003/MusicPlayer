package com.example.musicplayer;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Per-track listening statistics: play count and last-played time, keyed by
 * MediaStore audio ID. Backs the "Most played" and "Recently played"
 * auto-playlists and the stats shown in Track Details.
 *
 * <p>A "play" is recorded by {@link MusicService} once the user has actually
 * listened to half the track (capped at 4 minutes), or when it plays to the
 * end, so skipping through tracks doesn't inflate the counts.</p>
 *
 * <p>Stored as one JSON object in SharedPreferences, cached in memory.
 * Thread-safe.</p>
 */
public final class PlayStatsManager {

    private static final String TAG = "PlayStatsManager";
    private static final String PREFS_NAME = "play_stats";
    private static final String KEY_STATS = "stats_json";

    /** Virtual playlist IDs (Favorites already uses -1). */
    public static final long MOST_PLAYED_ID = -2;
    public static final long RECENTLY_PLAYED_ID = -3;
    /** Size cap for both auto-playlists. */
    public static final int AUTO_PLAYLIST_LIMIT = 100;

    public static final class Stat {
        public final int count;
        public final long lastPlayed; // epoch millis, 0 = never

        Stat(int count, long lastPlayed) {
            this.count = count;
            this.lastPlayed = lastPlayed;
        }
    }

    private static PlayStatsManager instance;

    private final SharedPreferences prefs;
    private final Map<Long, Stat> stats = new HashMap<>();

    private PlayStatsManager(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        load();
    }

    public static synchronized PlayStatsManager getInstance(Context context) {
        if (instance == null) instance = new PlayStatsManager(context);
        return instance;
    }

    /** Record one completed listen of a track. */
    public synchronized void recordPlay(long songId) {
        Stat old = stats.get(songId);
        int count = old != null ? old.count + 1 : 1;
        stats.put(songId, new Stat(count, System.currentTimeMillis()));
        save();
    }

    public synchronized Stat getStat(long songId) {
        Stat s = stats.get(songId);
        return s != null ? s : new Stat(0, 0);
    }

    /** Song IDs ordered by play count (ties: most recent first). */
    public synchronized List<Long> getMostPlayedIds(int limit) {
        List<Map.Entry<Long, Stat>> entries = new ArrayList<>(stats.entrySet());
        entries.sort((a, b) -> {
            int c = Integer.compare(b.getValue().count, a.getValue().count);
            return c != 0 ? c : Long.compare(b.getValue().lastPlayed, a.getValue().lastPlayed);
        });
        return idsOf(entries, limit);
    }

    /** Song IDs ordered by last-played time, newest first. */
    public synchronized List<Long> getRecentlyPlayedIds(int limit) {
        List<Map.Entry<Long, Stat>> entries = new ArrayList<>(stats.entrySet());
        entries.sort((a, b) -> Long.compare(b.getValue().lastPlayed, a.getValue().lastPlayed));
        return idsOf(entries, limit);
    }

    public synchronized int getTotalPlays() {
        int total = 0;
        for (Stat s : stats.values()) total += s.count;
        return total;
    }

    public synchronized boolean isEmpty() {
        return stats.isEmpty();
    }

    /** Forget all listening history. */
    public synchronized void clear() {
        stats.clear();
        save();
    }

    /** Drop stats for tracks that no longer exist on the device. */
    public synchronized void prune(java.util.Set<Long> existingIds) {
        if (stats.keySet().retainAll(existingIds)) save();
    }

    // ── Persistence ─────────────────────────────────────────

    private static List<Long> idsOf(List<Map.Entry<Long, Stat>> entries, int limit) {
        List<Long> ids = new ArrayList<>();
        for (Map.Entry<Long, Stat> e : entries) {
            if (ids.size() >= limit) break;
            ids.add(e.getKey());
        }
        return ids;
    }

    private void load() {
        try {
            JSONObject root = new JSONObject(prefs.getString(KEY_STATS, "{}"));
            Iterator<String> keys = root.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                JSONObject o = root.optJSONObject(key);
                if (o == null) continue;
                stats.put(Long.parseLong(key), new Stat(o.optInt("c", 0), o.optLong("t", 0)));
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not load play stats — starting fresh", e);
            stats.clear();
        }
    }

    private void save() {
        try {
            JSONObject root = new JSONObject();
            for (Map.Entry<Long, Stat> e : stats.entrySet()) {
                JSONObject o = new JSONObject();
                o.put("c", e.getValue().count);
                o.put("t", e.getValue().lastPlayed);
                root.put(String.valueOf(e.getKey()), o);
            }
            prefs.edit().putString(KEY_STATS, root.toString()).apply();
        } catch (Exception e) {
            Log.w(TAG, "Could not save play stats", e);
        }
    }
}
