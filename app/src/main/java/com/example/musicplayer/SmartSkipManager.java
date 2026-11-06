package com.example.musicplayer;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Smart Skip: learns, per song, the parts the listener habitually skips.
 *
 * <ul>
 *   <li><b>Intro</b> — the user seeks forward right after a track starts
 *       (e.g. 0:03 → 0:41). Once two such jumps land within
 *       {@link #AGREEMENT_MS} of each other, the player starts the track at the
 *       earlier of the two points.</li>
 *   <li><b>Outro</b> — the user presses Next late in a track (e.g. at 3:50 of
 *       4:30). Once two such presses agree, the player advances automatically
 *       at the later of the two points.</li>
 * </ul>
 *
 * <p>Both choices are conservative (skip the least content the user has shown
 * they skip). Seeking back to the start after an automatic intro skip is
 * treated as "undo" and forgets that song's intro.</p>
 */
public final class SmartSkipManager {

    private static final String TAG = "SmartSkip";
    private static final String PREFS_NAME = "smart_skip";
    private static final String KEY_DATA = "data_json";
    private static final String KEY_ENABLED = "enabled";

    /** Two observations must land this close together to be trusted. */
    static final long AGREEMENT_MS = 5_000;
    /** Only remember the last few observations per song. */
    private static final int MAX_OBSERVATIONS = 3;

    // Intro learning window
    /** A seek only counts as an intro skip if it starts within this time of track start. */
    static final long INTRO_SEEK_FROM_MAX_MS = 15_000;
    /** ...jumps at least this far forward... */
    static final long INTRO_MIN_JUMP_MS = 5_000;
    /** ...and lands no later than this. */
    static final long INTRO_TARGET_MAX_MS = 120_000;

    // Outro learning window
    /** Next counts as an outro skip only after this fraction of the track. */
    static final float OUTRO_MIN_FRACTION = 0.6f;
    /** ...and only if at least this much of the track was left. */
    static final long OUTRO_MIN_REMAINING_MS = 5_000;

    private static SmartSkipManager instance;

    private final SharedPreferences prefs;
    private final Map<Long, Entry> entries = new HashMap<>();

    private static final class Entry {
        final List<Long> intros = new ArrayList<>();
        final List<Long> outros = new ArrayList<>();
        boolean isEmpty() { return intros.isEmpty() && outros.isEmpty(); }
    }

    private SmartSkipManager(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        load();
    }

    public static synchronized SmartSkipManager getInstance(Context context) {
        if (instance == null) instance = new SmartSkipManager(context);
        return instance;
    }

    // ── Settings ────────────────────────────────────────────

    public boolean isEnabled() {
        return prefs.getBoolean(KEY_ENABLED, true);
    }

    public void setEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    // ── Learning ────────────────────────────────────────────

    /** The user jumped forward to {@code targetMs} right after the track started. */
    public synchronized void recordIntroSkip(long songId, long targetMs) {
        add(entry(songId).intros, targetMs);
        save();
    }

    /** The user pressed Next at {@code positionMs}, late in the track. */
    public synchronized void recordOutroSkip(long songId, long positionMs) {
        add(entry(songId).outros, positionMs);
        save();
    }

    /** The user undid an automatic intro skip — stop skipping this song's intro. */
    public synchronized void forgetIntro(long songId) {
        Entry e = entries.get(songId);
        if (e == null) return;
        e.intros.clear();
        if (e.isEmpty()) entries.remove(songId);
        save();
    }

    public synchronized void forgetAll() {
        entries.clear();
        save();
    }

    public synchronized int learnedSongCount() {
        int n = 0;
        for (Map.Entry<Long, Entry> e : entries.entrySet()) {
            if (agreed(e.getValue().intros, true) > 0 || agreed(e.getValue().outros, false) > 0) n++;
        }
        return n;
    }

    // ── Queries ─────────────────────────────────────────────

    /** Position to start the track at, or 0 if no intro skip has been learned. */
    public synchronized long getLearnedIntroMs(long songId) {
        Entry e = entries.get(songId);
        return e != null ? agreed(e.intros, true) : 0;
    }

    /** Position at which to auto-advance, or 0 if no outro skip has been learned. */
    public synchronized long getLearnedOutroMs(long songId) {
        Entry e = entries.get(songId);
        return e != null ? agreed(e.outros, false) : 0;
    }

    /**
     * Returns a learned position when the two most recent observations agree:
     * the earlier one for intros (skip less), the later one for outros (play more).
     */
    private static long agreed(List<Long> obs, boolean pickEarlier) {
        if (obs.size() < 2) return 0;
        long a = obs.get(obs.size() - 1);
        long b = obs.get(obs.size() - 2);
        if (Math.abs(a - b) > AGREEMENT_MS) return 0;
        return pickEarlier ? Math.min(a, b) : Math.max(a, b);
    }

    // ── Persistence ─────────────────────────────────────────

    private Entry entry(long songId) {
        Entry e = entries.get(songId);
        if (e == null) {
            e = new Entry();
            entries.put(songId, e);
        }
        return e;
    }

    private static void add(List<Long> list, long value) {
        list.add(value);
        while (list.size() > MAX_OBSERVATIONS) list.remove(0);
    }

    private void load() {
        try {
            JSONObject root = new JSONObject(prefs.getString(KEY_DATA, "{}"));
            Iterator<String> keys = root.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                JSONObject o = root.optJSONObject(key);
                if (o == null) continue;
                Entry e = new Entry();
                readList(o.optJSONArray("i"), e.intros);
                readList(o.optJSONArray("o"), e.outros);
                if (!e.isEmpty()) entries.put(Long.parseLong(key), e);
            }
        } catch (Exception ex) {
            Log.w(TAG, "Could not load smart skip data — starting fresh", ex);
            entries.clear();
        }
    }

    private static void readList(JSONArray arr, List<Long> out) {
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) out.add(arr.optLong(i));
    }

    private void save() {
        try {
            JSONObject root = new JSONObject();
            for (Map.Entry<Long, Entry> e : entries.entrySet()) {
                JSONObject o = new JSONObject();
                o.put("i", new JSONArray(e.getValue().intros));
                o.put("o", new JSONArray(e.getValue().outros));
                root.put(String.valueOf(e.getKey()), o);
            }
            prefs.edit().putString(KEY_DATA, root.toString()).apply();
        } catch (Exception ex) {
            Log.w(TAG, "Could not save smart skip data", ex);
        }
    }
}
