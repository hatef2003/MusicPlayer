package com.example.musicplayer;

import android.content.Context;
import android.content.SharedPreferences;

import com.example.musicplayer.model.Song;
import com.example.musicplayer.model.Playlist;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Persists Telegram browsing data so it survives app restarts and offline use. */
public class TelegramCache {
    private static final String PREFS = "telegram_cache";
    private static final String KEY_GROUPS = "groups";
    private final SharedPreferences prefs;

    public TelegramCache(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public void save(List<TelegramModels.TelegramGroup> groups) {
        try {
            JSONArray groupsJson = new JSONArray();
            for (TelegramModels.TelegramGroup group : groups) {
                JSONObject groupJson = new JSONObject();
                groupJson.put("chatId", group.chatId);
                groupJson.put("title", group.title);
                groupJson.put("type", group.type);
                JSONArray topicsJson = new JSONArray();
                for (TelegramModels.TelegramTopic topic : group.topics) {
                    JSONObject topicJson = new JSONObject();
                    topicJson.put("threadId", topic.threadId);
                    topicJson.put("name", topic.name);
                    JSONArray tracksJson = new JSONArray();
                    for (TelegramModels.TelegramTrack track : topic.tracks) {
                        JSONObject trackJson = new JSONObject();
                        trackJson.put("messageId", track.messageId);
                        trackJson.put("fileName", track.fileName);
                        trackJson.put("title", track.title);
                        trackJson.put("performer", track.performer);
                        trackJson.put("fileSize", track.fileSize);
                        trackJson.put("fileId", track.fileId);
                        trackJson.put("mimeType", track.mimeType);
                        trackJson.put("caption", track.caption);
                        trackJson.put("duration", track.duration);
                        tracksJson.put(trackJson);
                    }
                    topicJson.put("tracks", tracksJson);
                    topicsJson.put(topicJson);
                }
                groupJson.put("topics", topicsJson);
                groupsJson.put(groupJson);
            }
            prefs.edit().putString(KEY_GROUPS, groupsJson.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    public List<TelegramModels.TelegramGroup> load() {
        List<TelegramModels.TelegramGroup> groups = new ArrayList<>();
        try {
            JSONArray groupsJson = new JSONArray(prefs.getString(KEY_GROUPS, "[]"));
            for (int i = 0; i < groupsJson.length(); i++) {
                JSONObject groupJson = groupsJson.getJSONObject(i);
                TelegramModels.TelegramGroup group = new TelegramModels.TelegramGroup(
                        groupJson.getString("chatId"), groupJson.getString("title"), groupJson.optString("type", "supergroup"));
                JSONArray topicsJson = groupJson.optJSONArray("topics");
                if (topicsJson != null) {
                    for (int j = 0; j < topicsJson.length(); j++) {
                        JSONObject topicJson = topicsJson.getJSONObject(j);
                        TelegramModels.TelegramTopic topic = new TelegramModels.TelegramTopic(
                                topicJson.getInt("threadId"), topicJson.getString("name"));
                        JSONArray tracksJson = topicJson.optJSONArray("tracks");
                        if (tracksJson != null) {
                            for (int k = 0; k < tracksJson.length(); k++) {
                                JSONObject t = tracksJson.getJSONObject(k);
                                topic.tracks.add(new TelegramModels.TelegramTrack(
                                        t.getLong("messageId"), t.getString("fileName"), t.optString("title", ""),
                                        t.optString("performer", ""), t.optLong("fileSize", 0), t.getString("fileId"),
                                        t.optString("mimeType", "audio/mpeg"), t.optString("caption", ""), t.optInt("duration", 0)));
                            }
                        }
                        group.topics.add(topic);
                    }
                }
                groups.add(group);
            }
        } catch (Exception ignored) {
        }
        return groups;
    }

    public void syncPlaylists(Context context, List<TelegramModels.TelegramGroup> groups, List<Song> songs) {
        PlaylistManager manager = PlaylistManager.getInstance(context);
        for (TelegramModels.TelegramGroup group : groups) {
            for (TelegramModels.TelegramTopic topic : group.topics) {
                String name = topic.name;
                Playlist playlist = findByName(manager.getAllPlaylists(), name);
                if (playlist == null) playlist = manager.createPlaylist(name);
                for (TelegramModels.TelegramTrack track : topic.tracks) {
                    Song local = resolve(track, songs);
                    if (local != null) manager.addSongToPlaylist(playlist.getId(), local.getId());
                }
            }
        }
    }

    private Playlist findByName(List<Playlist> playlists, String name) {
        for (Playlist playlist : playlists) if (playlist.getName().equals(name)) return playlist;
        return null;
    }

    public static Song resolve(TelegramModels.TelegramTrack track, List<Song> songs) {
        String fileName = normalize(track.fileName);
        String title = normalize(track.title);
        String performer = normalize(track.performer);
        for (Song song : songs) {
            String uriName = normalize(song.getContentUri().toString());
            if (!fileName.isEmpty() && uriName.endsWith(fileName)) return song;
        }
        for (Song song : songs) {
            boolean titleMatches = !title.isEmpty() && normalize(song.getTitle()).equals(title);
            boolean artistMatches = performer.isEmpty() || normalize(song.getArtist()).equals(performer);
            if (titleMatches && artistMatches) return song;
        }
        return null;
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return value.toLowerCase(java.util.Locale.US).replaceAll("%20", " ")
                .replaceAll("[^a-z0-9]+", " ").trim();
    }
}
