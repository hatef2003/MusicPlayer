package com.example.musicplayer;

import android.content.Context;
import android.content.SharedPreferences;

import com.example.musicplayer.model.Playlist;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages playlist persistence using SharedPreferences + JSON.
 */
public class PlaylistManager {

    private static final String PREFS_NAME = "playlists";
    private static final String KEY_PLAYLISTS = "playlists_json";

    private static PlaylistManager instance;
    private final SharedPreferences prefs;

    public interface OnPlaylistsChangedListener {
        void onPlaylistsChanged();
    }

    private OnPlaylistsChangedListener listener;

    private PlaylistManager(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static synchronized PlaylistManager getInstance(Context context) {
        if (instance == null) {
            instance = new PlaylistManager(context);
        }
        return instance;
    }

    public void setOnPlaylistsChangedListener(OnPlaylistsChangedListener listener) {
        this.listener = listener;
    }

    // ── CRUD ──────────────────────────────────────────────────────

    public List<Playlist> getAllPlaylists() {
        return deserialize(prefs.getString(KEY_PLAYLISTS, "[]"));
    }

    public Playlist getPlaylist(long id) {
        for (Playlist p : getAllPlaylists()) {
            if (p.getId() == id) return p;
        }
        return null;
    }

    public Playlist createPlaylist(String name) {
        List<Playlist> all = getAllPlaylists();
        long newId = System.currentTimeMillis();
        Playlist p = new Playlist(newId, name, new ArrayList<>(), newId);
        all.add(p);
        save(all);
        notifyChanged();
        return p;
    }

    public void renamePlaylist(long id, String newName) {
        List<Playlist> all = getAllPlaylists();
        for (Playlist p : all) {
            if (p.getId() == id) {
                p.setName(newName);
                break;
            }
        }
        save(all);
        notifyChanged();
    }

    public void deletePlaylist(long id) {
        List<Playlist> all = getAllPlaylists();
        all.removeIf(p -> p.getId() == id);
        save(all);
        notifyChanged();
    }

    public void addSongToPlaylist(long playlistId, long songId) {
        List<Playlist> all = getAllPlaylists();
        for (Playlist p : all) {
            if (p.getId() == playlistId) {
                p.addSong(songId);
                break;
            }
        }
        save(all);
        notifyChanged();
    }

    public void removeSongFromPlaylist(long playlistId, long songId) {
        List<Playlist> all = getAllPlaylists();
        for (Playlist p : all) {
            if (p.getId() == playlistId) {
                p.removeSong(songId);
                break;
            }
        }
        save(all);
        notifyChanged();
    }

    // ── Serialization ─────────────────────────────────────────────

    private void save(List<Playlist> playlists) {
        prefs.edit().putString(KEY_PLAYLISTS, serialize(playlists)).apply();
    }

    private String serialize(List<Playlist> playlists) {
        try {
            JSONArray arr = new JSONArray();
            for (Playlist p : playlists) {
                JSONObject obj = new JSONObject();
                obj.put("id", p.getId());
                obj.put("name", p.getName());
                obj.put("dateCreated", p.getDateCreated());
                JSONArray ids = new JSONArray();
                for (long songId : p.getSongIds()) {
                    ids.put(songId);
                }
                obj.put("songIds", ids);
                arr.put(obj);
            }
            return arr.toString();
        } catch (Exception e) {
            return "[]";
        }
    }

    private List<Playlist> deserialize(String json) {
        List<Playlist> playlists = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                long id = obj.getLong("id");
                String name = obj.getString("name");
                long dateCreated = obj.getLong("dateCreated");
                JSONArray ids = obj.getJSONArray("songIds");
                List<Long> songIds = new ArrayList<>();
                for (int j = 0; j < ids.length(); j++) {
                    songIds.add(ids.getLong(j));
                }
                playlists.add(new Playlist(id, name, songIds, dateCreated));
            }
        } catch (Exception e) {
            // Return empty list on parse error
        }
        return playlists;
    }

    private void notifyChanged() {
        if (listener != null) {
            listener.onPlaylistsChanged();
        }
    }
}
