package com.example.musicplayer;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Manages favorite songs using SharedPreferences.
 * Stores a set of song IDs (MediaStore audio IDs).
 */
public class FavoritesManager {

    private static final String PREFS_NAME = "music_favorites";
    private static final String KEY_FAVORITES = "favorite_ids";

    private final SharedPreferences prefs;

    public FavoritesManager(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** Check whether a song is favorited. */
    public boolean isFavorite(long songId) {
        return getFavoriteIds().contains(songId);
    }

    /** Toggle favorite state. Returns true if now favorited, false if unfavorited. */
    public boolean toggleFavorite(long songId) {
        Set<Long> ids = getFavoriteIds();
        boolean nowFavorite;
        if (ids.contains(songId)) {
            ids.remove(songId);
            nowFavorite = false;
        } else {
            ids.add(songId);
            nowFavorite = true;
        }
        saveFavoriteIds(ids);
        return nowFavorite;
    }

    /** Get all favorited song IDs. */
    public Set<Long> getFavoriteIds() {
        Set<String> stringSet = prefs.getStringSet(KEY_FAVORITES, Collections.emptySet());
        Set<Long> longs = new HashSet<>();
        if (stringSet != null) {
            for (String s : stringSet) {
                try {
                    longs.add(Long.parseLong(s));
                } catch (NumberFormatException ignored) { }
            }
        }
        return longs;
    }

    private void saveFavoriteIds(Set<Long> ids) {
        Set<String> stringSet = new HashSet<>();
        for (long id : ids) {
            stringSet.add(String.valueOf(id));
        }
        prefs.edit().putStringSet(KEY_FAVORITES, stringSet).apply();
    }
}
