package com.example.musicplayer;

import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;

import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;


import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.content.ContentUris;
import android.net.Uri;
import android.provider.MediaStore;

import com.example.musicplayer.model.Song;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Telegram tab fragment — browses forum groups → topics → music tracks.
 *
 * Navigation states:
 *   GROUPS  → tap group → TOPICS → tap topic → TRACKS → tap download
 */
public class TelegramFragment extends Fragment {

    // ── Navigation states ───────────────────────────────────
    private enum NavState { GROUPS, TOPICS, TRACKS }

    private NavState navState = NavState.GROUPS;
    private TelegramModels.TelegramGroup currentGroup;
    private TelegramModels.TelegramTopic currentTopic;

    // ── UI ──────────────────────────────────────────────────
    private RecyclerView list;
    private TextView emptyText;
    private TextView statusText;
    private TextView refreshBtn;
    private ProgressBar progressBar;
    private TextView exportBtn;

    // ── Data ────────────────────────────────────────────────
    private final List<Object> displayItems = new ArrayList<>();
    private TelegramListAdapter adapter;

    // ── Telegram ────────────────────────────────────────────
    private TelegramBotClient client;
    private TelegramDownloadManager downloadManager;
    private final Map<String, TelegramModels.TelegramGroup> groupsById = new HashMap<>();
    private TelegramCache cache;
    private List<Song> localSongs = new ArrayList<>();
    private long lastUpdateId = 0;
    private boolean isSyncing = false;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.activity_telegram, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        list = view.findViewById(R.id.tgList);
        emptyText = view.findViewById(R.id.tgEmpty);
        statusText = view.findViewById(R.id.tgStatus);
        refreshBtn = view.findViewById(R.id.tgRefreshBtn);
        progressBar = view.findViewById(R.id.tgProgressBar);
        exportBtn = view.findViewById(R.id.tgExportBtn);
        exportBtn.setOnClickListener(v -> exportCurrentTopicFile());
        cache = new TelegramCache(requireContext());
        localSongs = MusicRepository.loadAllSongs(requireContext());
        for (TelegramModels.TelegramGroup group : cache.load()) groupsById.put(group.chatId, group);
        // Resume from the last confirmed update so restarts continue the sync
        // incrementally instead of starting over and losing previously-synced topics.
        lastUpdateId = cache.loadLastUpdateId();

        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        final int baseBottomPadding = (int) (32 * getResources().getDisplayMetrics().density);
        ViewCompat.setOnApplyWindowInsetsListener(list, (v, insets) -> {
            int navigationBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(),
                    baseBottomPadding + navigationBottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(list);
        adapter = new TelegramListAdapter();
        list.setAdapter(adapter);

        refreshBtn.setOnClickListener(v -> onRefresh());

        restoreClient();
        refreshDisplay();

        // Pull anything new since the last sync, merging it into the cached
        // structure already on screen (never replacing it).
        if (client != null && !isSyncing) onRefresh();
    }

    @Override
    public void onResume() {
        super.onResume();
        restoreClient();
    }

    @Override
    public void onDestroy() {
        if (client != null) {
            client.shutdown();
            client = null;
        }
        downloadManager = null;
        super.onDestroy();
    }

    private void restoreClient() {
        SettingsManager sm = new SettingsManager(requireContext());
        // Normalize exactly as TelegramBotClient does, so comparing against
        // client.getBaseUrl()/getBotToken() below doesn't see a phantom change
        // (e.g. a trailing "/") and rebuild the client on every resume.
        String token = TelegramBotClient.normalizeToken(sm.getTelegramBotToken());
        String baseUrl = TelegramBotClient.normalizeBaseUrl(sm.getTelegramBaseUrl());

        if (token.isEmpty()) {
            if (client != null) client.shutdown();
            isSyncing = false;
            client = null;
            downloadManager = null;
            showEmpty("No bot token configured.\n\nGo to Settings → Telegram Bot Token\nand paste your token from @BotFather.");
            return;
        }

        String configSignature = baseUrl + "|" + token;

        // Decide whether the bot configuration actually changed. On a fresh
        // process `client` is null but groupsById already holds the structure
        // restored from TelegramCache above — that must NOT be discarded, or
        // every restart would drop every topic except whichever ones receive
        // new messages afterwards ("only the last topic shows"). Only reset
        // when the cached data carries a signature for a *different* bot; a
        // legacy cache without a signature is adopted as-is.
        boolean configChanged;
        if (client == null) {
            configChanged = cache.hasConfigSignature()
                    && !cache.matchesConfigSignature(configSignature);
        } else {
            configChanged = !client.getBotToken().equals(token)
                    || !client.getBaseUrl().equals(baseUrl);
        }

        if (client == null || configChanged) {
            if (client != null) {
                // Release the old bot's threads. Its in-flight getUpdates is
                // dropped and will never call back, so clear the sync flag —
                // otherwise the tab stays on "syncing..." and Refresh is ignored.
                client.shutdown();
                if (isSyncing) {
                    isSyncing = false;
                    showProgress(false);
                }
            }
            client = new TelegramBotClient(token, baseUrl);
            downloadManager = new TelegramDownloadManager(requireContext(), client);
        }

        if (configChanged) {
            // Different bot / base URL configured → start syncing it fresh.
            groupsById.clear();
            lastUpdateId = 0;
            cache.saveState(new ArrayList<>(), 0, configSignature);
        }
    }

    // ── Refresh ─────────────────────────────────────────────

    private void onRefresh() {
        restoreClient();
        if (client == null) return;
        if (isSyncing) return;

        isSyncing = true;
        showProgress(true);
        statusText.setText("syncing...");

        client.getUpdates(lastUpdateId + 1, 0,
                this::processUpdates,
                error -> {
                    isSyncing = false;
                    showProgress(false);
                    statusText.setText("error: " + error);
                });
    }

    private void processUpdates(JSONArray updates) {
        try {
            for (int i = 0; i < updates.length(); i++) {
                JSONObject update = updates.getJSONObject(i);
                long updateId = update.getLong("update_id");
                if (updateId > lastUpdateId) lastUpdateId = updateId;

                // Handle chat membership updates (bot added to group)
                if (update.has("my_chat_member")) {
                    handleChatMemberUpdate(update.getJSONObject("my_chat_member"));
                }

                // Handle messages (audio, documents, text)
                if (update.has("message")) {
                    handleMessage(update.getJSONObject("message"));
                }
            }
        } catch (Exception e) {
            android.util.Log.e("TelegramFragment", "processUpdates failed", e);
        }

        persistState();
        isSyncing = false;
        showProgress(false);
        refreshDisplay();
    }

    /** Persist current groups together with the sync offset and bot config. */
    private void persistState() {
        String signature = client != null ? client.getBaseUrl() + "|" + client.getBotToken() : "";
        cache.saveState(new ArrayList<>(groupsById.values()), lastUpdateId, signature);
    }

    private void handleChatMemberUpdate(JSONObject chatMember) {
        try {
            JSONObject chat = chatMember.getJSONObject("chat");
            String chatId = String.valueOf(chat.getLong("id"));
            String title = chat.optString("title", "Unknown Group");
            String type = chat.optString("type", "supergroup");

            if (!groupsById.containsKey(chatId)) {
                TelegramModels.TelegramGroup group = new TelegramModels.TelegramGroup(chatId, title, type);
                groupsById.put(chatId, group);
            }
        } catch (Exception e) {
            android.util.Log.w("TelegramFragment", "handleChatMemberUpdate", e);
        }
    }

    private void handleMessage(JSONObject message) {
        try {
            JSONObject chat = message.getJSONObject("chat");
            String chatId = String.valueOf(chat.getLong("id"));
            String chatTitle = chat.optString("title", "Unknown Group");

            // Ensure group exists
            TelegramModels.TelegramGroup group = groupsById.get(chatId);
            if (group == null) {
                group = new TelegramModels.TelegramGroup(chatId, chatTitle, chat.optString("type", "supergroup"));
                groupsById.put(chatId, group);
            }

            int threadId = message.optInt("message_thread_id", 0);
            if (threadId == 0) return; // Not in a forum topic

            String topicName = message.optString("forum_topic_name", "");
            if (topicName.isEmpty()) topicName = message.optString("topic_name", "");

            // Handle forum topic creation service message
            if (message.has("forum_topic_created")) {
                JSONObject topicInfo = message.getJSONObject("forum_topic_created");
                String createdTopicName = topicInfo.optString("name", "Topic " + threadId);
                TelegramModels.TelegramTopic topic = getOrCreateTopic(group, threadId, createdTopicName);
                // Also apply the real name if this thread already exists (e.g.
                // its first audio arrived before the creation event).
                if (!createdTopicName.isEmpty()) topic.name = createdTopicName;
                return;
            }

            // Handle topic name changes
            if (message.has("forum_topic_edited")) {
                JSONObject editInfo = message.getJSONObject("forum_topic_edited");
                String newName = editInfo.optString("name", "");
                TelegramModels.TelegramTopic topic = findTopic(group, threadId);
                if (topic != null && !newName.isEmpty()) topic.name = newName;
                return;
            }

            // Check for audio file
            if (message.has("audio")) {
                JSONObject audio = message.getJSONObject("audio");
                TelegramModels.TelegramTrack track = parseAudioTrack(message, audio);
                if (track != null) {
                    TelegramModels.TelegramTopic topic = topicForMessage(group, threadId, topicName);
                    addTrackIfNew(topic, track);
                }
            }

            // Check for document (might be an audio file uploaded as document)
            if (message.has("document")) {
                JSONObject doc = message.getJSONObject("document");
                String mimeType = doc.optString("mime_type", "");
                if (mimeType.startsWith("audio/") || isAudioExtension(doc.optString("file_name", ""))) {
                    TelegramModels.TelegramTrack track = parseDocumentTrack(message, doc);
                    if (track != null) {
                        TelegramModels.TelegramTopic topic = topicForMessage(group, threadId, topicName);
                        addTrackIfNew(topic, track);
                    }
                }
            }

            // Check voice message
            if (message.has("voice")) {
                JSONObject voice = message.getJSONObject("voice");
                if (threadId != 0) {
                    TelegramModels.TelegramTopic topic = topicForMessage(group, threadId, topicName);
                    String caption = message.optString("caption", "");
                    TelegramModels.TelegramTrack track = new TelegramModels.TelegramTrack(
                            message.getLong("message_id"),
                            "voice_" + message.getLong("message_id") + ".ogg",
                            caption.isEmpty() ? "Voice message" : caption,
                            "",
                            voice.optLong("file_size", 0),
                            voice.getString("file_id"),
                            voice.optString("mime_type", "audio/ogg"),
                            caption,
                            voice.optInt("duration", 0)
                    );
                    addTrackIfNew(topic, track);
                }
            }

        } catch (Exception e) {
            android.util.Log.w("TelegramFragment", "handleMessage", e);
        }
    }

    private TelegramModels.TelegramTrack parseAudioTrack(JSONObject message, JSONObject audio) throws Exception {
        long messageId = message.getLong("message_id");
        String title = audio.optString("title", "");
        String performer = audio.optString("performer", "");
        String fileName = audio.optString("file_name", "");
        if (fileName.isEmpty()) fileName = (performer.isEmpty() ? "track" : performer) + " - " + title + ".mp3";
        String caption = message.optString("caption", "");

        return new TelegramModels.TelegramTrack(
                messageId, fileName,
                title.isEmpty() ? fileName : title,
                performer,
                audio.optLong("file_size", 0),
                audio.getString("file_id"),
                audio.optString("mime_type", "audio/mpeg"),
                caption,
                audio.optInt("duration", 0)
        );
    }

    private TelegramModels.TelegramTrack parseDocumentTrack(JSONObject message, JSONObject doc) throws Exception {
        long messageId = message.getLong("message_id");
        String fileName = doc.optString("file_name", "track_" + messageId);
        String caption = message.optString("caption", "");

        return new TelegramModels.TelegramTrack(
                messageId, fileName,
                caption.isEmpty() ? fileName : caption,
                "",
                doc.optLong("file_size", 0),
                doc.getString("file_id"),
                doc.optString("mime_type", "audio/mpeg"),
                caption,
                0
        );
    }

    private boolean isAudioExtension(String fileName) {
        String lower = fileName.toLowerCase(java.util.Locale.US);
        return lower.endsWith(".mp3") || lower.endsWith(".flac") || lower.endsWith(".wav")
                || lower.endsWith(".ogg") || lower.endsWith(".m4a") || lower.endsWith(".aac")
                || lower.endsWith(".wma") || lower.endsWith(".opus");
    }

    private TelegramModels.TelegramTopic getOrCreateTopic(TelegramModels.TelegramGroup group, int threadId, String name) {
        for (TelegramModels.TelegramTopic t : group.topics) {
            if (t.threadId == threadId) return t;
        }
        TelegramModels.TelegramTopic topic = new TelegramModels.TelegramTopic(threadId, name);
        group.topics.add(topic);
        return topic;
    }

    /**
     * Gets (or creates) the topic for a message and applies a topic name whenever
     * the update happens to carry one. Regular Bot API messages never include the
     * topic name — it only arrives in forum_topic_created/edited service messages —
     * so this also lets local Bot API servers / future API versions fix a topic
     * that was first seen as a bare "Topic &lt;id&gt;" placeholder.
     */
    private TelegramModels.TelegramTopic topicForMessage(TelegramModels.TelegramGroup group, int threadId, String topicName) {
        TelegramModels.TelegramTopic topic = getOrCreateTopic(group, threadId,
                topicName.isEmpty() ? "Topic " + threadId : topicName);
        if (!topicName.isEmpty() && !topicName.equals(topic.name)) topic.name = topicName;
        return topic;
    }

    /**
     * Telegram only reveals topic names through creation/rename events the bot
     * actually receives, so topics that pre-date the bot show up as "Topic &lt;id&gt;".
     * Long-pressing the row lets the user set the real name locally (persisted).
     */
    private void showRenameTopicDialog(TelegramModels.TelegramTopic topic) {
        if (topic == null) return;
        EditText input = new EditText(requireContext());
        input.setSingleLine(true);
        input.setText(topic.name);
        input.setSelectAllOnFocus(true);
        input.setHint("Topic name");
        int padding = (int) (24 * getResources().getDisplayMetrics().density);
        input.setPadding(padding, padding / 2, padding, padding / 2);
        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("Rename Topic")
                .setMessage("Only changes the name shown in this app.")
                .setView(input)
                .setPositiveButton("SAVE", (dialog, which) -> {
                    String newName = input.getText().toString().trim();
                    if (newName.isEmpty() || newName.equals(topic.name)) return;
                    topic.name = newName;
                    persistState();
                    refreshDisplay();
                })
                .setNegativeButton("CANCEL", null)
                .show();
    }

    private TelegramModels.TelegramTopic findTopic(TelegramModels.TelegramGroup group, int threadId) {
        for (TelegramModels.TelegramTopic t : group.topics) {
            if (t.threadId == threadId) return t;
        }
        return null;
    }

    private void addTrackIfNew(TelegramModels.TelegramTopic topic, TelegramModels.TelegramTrack track) {
        for (TelegramModels.TelegramTrack existing : topic.tracks) {
            if (existing.messageId == track.messageId) return;
        }
        topic.tracks.add(track);
    }

    // ── Display ─────────────────────────────────────────────

    private void refreshDisplay() {
        displayItems.clear();
        exportBtn.setVisibility(navState == NavState.TRACKS ? View.VISIBLE : View.GONE);

        switch (navState) {
            case GROUPS:
                statusText.setText("groups (" + groupsById.size() + ")");
                if (groupsById.isEmpty()) {
                    showEmpty("No groups found yet.\n\n" +
                            "1. Add your bot as admin to a forum group\n" +
                            "2. Make sure privacy mode is OFF in @BotFather\n" +
                            "3. Send a test message in the group\n" +
                            "4. Tap REFRESH here");
                    return;
                }
                displayItems.addAll(groupsById.values());
                break;

            case TOPICS:
                statusText.setText(currentGroup.title + " > topics (" + currentGroup.topics.size() + ")");
                if (currentGroup.topics.isEmpty()) {
                    showEmpty("No topics found in this group.\nSend music files in forum topics to see them here.");
                    return;
                }
                displayItems.addAll(currentGroup.topics);
                break;

            case TRACKS:
                statusText.setText(currentGroup.title + " > " + currentTopic.name + " (" + currentTopic.tracks.size() + " tracks)");
                exportBtn.setVisibility(View.VISIBLE);
                exportBtn.setOnClickListener(v -> exportCurrentTopicFile());
                if (currentTopic.tracks.isEmpty()) {
                    showEmpty("No tracks found in this topic.\nSend audio files here to see them.");
                    return;
                }
                displayItems.addAll(currentTopic.tracks);
                break;
        }

        showEmpty(null);
        adapter.notifyDataSetChanged();
    }

    private void exportCurrentTopicFile() {
        if (currentTopic == null) return;
        EditText input = new EditText(requireContext());
        input.setSingleLine(true);
        input.setHint("Playlist name");
        input.setText(currentTopic.name);
        input.setSelectAllOnFocus(true);
        int padding = (int) (24 * getResources().getDisplayMetrics().density);
        input.setPadding(padding, padding / 2, padding, padding / 2);
        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("Save to Playlist")
                .setMessage("Choose a playlist name")
                .setView(input)
                .setPositiveButton("SAVE", (dialog, which) -> saveTopicAsPlaylist(input.getText().toString().trim()))
                .setNegativeButton("CANCEL", null)
                .show();
    }

    private void saveTopicAsPlaylist(String playlistName) {
        if (playlistName.isEmpty()) {
            Toast.makeText(requireContext(), "Playlist name cannot be empty", Toast.LENGTH_SHORT).show();
            return;
        }
        List<Song> songs = new ArrayList<>();
        for (TelegramModels.TelegramTrack track : currentTopic.tracks) {
            Song song = TelegramCache.resolve(track, localSongs);
            if (song != null) songs.add(song);
        }
        if (songs.isEmpty()) {
            Toast.makeText(requireContext(), "No local songs to save", Toast.LENGTH_SHORT).show();
            return;
        }
        PlaylistManager manager = PlaylistManager.getInstance(requireContext());
        com.example.musicplayer.model.Playlist playlist = null;
        for (com.example.musicplayer.model.Playlist existing : manager.getAllPlaylists()) {
            if (existing.getName().equalsIgnoreCase(playlistName)) {
                playlist = existing;
                break;
            }
        }
        if (playlist == null) playlist = manager.createPlaylist(playlistName);
        for (Song song : songs) manager.addSongToPlaylist(playlist.getId(), song.getId());
        Toast.makeText(requireContext(), "Saved \"" + playlistName + "\" to Playlists (" + songs.size() + " songs)", Toast.LENGTH_SHORT).show();
    }

    private void oldExportCurrentTopicFile() {
        if (currentTopic == null || currentGroup == null) return;

        List<Song> songs = new ArrayList<>();
        for (TelegramModels.TelegramTrack track : currentTopic.tracks) {
            Song song = TelegramCache.resolve(track, localSongs);
            if (song != null) songs.add(song);
        }
        if (songs.isEmpty()) {
            Toast.makeText(requireContext(), "No local songs to save", Toast.LENGTH_SHORT).show();
            return;
        }

        PlaylistManager manager = PlaylistManager.getInstance(requireContext());
        String playlistName = currentTopic.name;
        com.example.musicplayer.model.Playlist playlist = null;
        for (com.example.musicplayer.model.Playlist existing : manager.getAllPlaylists()) {
            if (existing.getName().equals(playlistName)) {
                playlist = existing;
                break;
            }
        }
        if (playlist == null) playlist = manager.createPlaylist(playlistName);

        for (Song song : songs) manager.addSongToPlaylist(playlist.getId(), song.getId());
        Toast.makeText(requireContext(),
                "Saved \"" + playlistName + "\" to Playlists (" + songs.size() + " songs)",
                Toast.LENGTH_SHORT).show();
    }

    private void showEmpty(String text) {
        if (text != null) {
            emptyText.setText(text);
            emptyText.setVisibility(View.VISIBLE);
            list.setVisibility(View.GONE);
        } else {
            emptyText.setVisibility(View.GONE);
            list.setVisibility(View.VISIBLE);
        }
    }

    private void showProgress(boolean show) {
        progressBar.setVisibility(show ? View.VISIBLE : View.GONE);
        refreshBtn.setEnabled(!show);
    }

    // ── Back navigation (called from MainActivity) ─────────

    public boolean handleBack() {
        switch (navState) {
            case TRACKS:
                navState = NavState.TOPICS;
                currentTopic = null;
                refreshDisplay();
                return true;
            case TOPICS:
                navState = NavState.GROUPS;
                currentGroup = null;
                refreshDisplay();
                return true;
            default:
                return false;
        }
    }

    // ── Adapter ─────────────────────────────────────────────

    private class TelegramListAdapter extends RecyclerView.Adapter<TelegramListAdapter.VH> {

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_telegram, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            Object item = displayItems.get(position);
            holder.progress.setVisibility(View.GONE);
            holder.progressText.setVisibility(View.GONE);

            if (item instanceof TelegramModels.TelegramGroup) {
                TelegramModels.TelegramGroup group = (TelegramModels.TelegramGroup) item;
                holder.itemView.setOnLongClickListener(null);
                holder.index.setText(String.format(java.util.Locale.US, "%02d", position + 1));
                holder.title.setText(group.title);
                holder.subtitle.setText(group.topics.size() + " topics");
                holder.action.setText("OPEN");
                holder.action.setOnClickListener(v -> {
                    currentGroup = group;
                    navState = NavState.TOPICS;
                    refreshDisplay();
                });

            } else if (item instanceof TelegramModels.TelegramTopic) {
                TelegramModels.TelegramTopic topic = (TelegramModels.TelegramTopic) item;
                holder.index.setText(String.format(java.util.Locale.US, "%02d", position + 1));
                holder.title.setText(topic.name);
                // Placeholder names (topic existed before the bot learned its
                // name) can be fixed by long-pressing the row — hint at it.
                boolean unnamed = topic.name.equals("Topic " + topic.threadId);
                holder.subtitle.setText((topic.tracks.size() + " tracks")
                        + (unnamed ? " • long-press to name" : ""));
                holder.action.setText("OPEN");
                holder.action.setOnClickListener(v -> {
                    currentTopic = topic;
                    navState = NavState.TRACKS;
                    refreshDisplay();
                });
                // Fix a "Topic <id>" placeholder by long-pressing the row.
                holder.itemView.setOnLongClickListener(v -> {
                    showRenameTopicDialog(topic);
                    return true;
                });

            } else if (item instanceof TelegramModels.TelegramTrack) {
                TelegramModels.TelegramTrack track = (TelegramModels.TelegramTrack) item;
                holder.itemView.setOnLongClickListener(null);
                holder.index.setText(String.format(java.util.Locale.US, "%02d", position + 1));
                holder.title.setText(track.getDisplayName());

                // Build subtitle
                StringBuilder sub = new StringBuilder();
                if (!track.getDurationLabel().isEmpty()) sub.append(track.getDurationLabel()).append(" • ");
                sub.append(track.getFileSizeLabel());
                if (track.fileSize > 20 * 1024 * 1024) sub.append(" [>20MB]");
                holder.subtitle.setText(sub.toString());

                Song localSong = TelegramCache.resolve(track, localSongs);
                if (localSong != null) {
                    holder.action.setText("PLAY");
                    holder.action.setOnClickListener(v -> playLocalSong(localSong));
                    holder.action.setTextColor(holder.itemView.getContext().getResources().getColor(R.color.terminal_green));
                    holder.title.setTextColor(holder.itemView.getContext().getResources().getColor(R.color.terminal_green));
                // Check if already downloaded
                } else if (downloadManager != null && downloadManager.isAlreadyDownloaded(track.fileName, track.fileSize)) {
                    holder.action.setText("PLAY");
                    holder.action.setOnClickListener(v -> playSavedTrack(track));
                    holder.action.setTextColor(holder.itemView.getContext().getResources().getColor(R.color.terminal_green));
                    holder.title.setTextColor(holder.itemView.getContext().getResources().getColor(R.color.terminal_green));
                } else if (track.fileSize > 20 * 1024 * 1024) {
                    holder.action.setText("LARGE");
                    holder.action.setOnClickListener(null);
                    holder.action.setTextColor(holder.itemView.getContext().getResources().getColor(R.color.text_secondary));
                } else {
                    holder.action.setText("GET");
                    holder.action.setOnClickListener(v -> startDownload(track, holder));
                    holder.action.setTextColor(holder.itemView.getContext().getResources().getColor(R.color.terminal_green));
                }
            }
        }

        @Override
        public int getItemCount() { return displayItems.size(); }

        class VH extends RecyclerView.ViewHolder {
            TextView index, title, subtitle, action, progressText;
            ProgressBar progress;

            VH(@NonNull View v) {
                super(v);
                index = v.findViewById(R.id.tgItemIndex);
                title = v.findViewById(R.id.tgItemTitle);
                subtitle = v.findViewById(R.id.tgItemSubtitle);
                action = v.findViewById(R.id.tgItemAction);
                progress = v.findViewById(R.id.tgItemProgress);
                progressText = v.findViewById(R.id.tgItemProgressText);
            }
        }
    }

    // ── Playback ────────────────────────────────────────────

    private void playLocalSong(Song song) {
        PlaybackManager pm = PlaybackManager.getInstance(requireContext());
        pm.bind();
        pm.play(Collections.singletonList(song), 0);
        Toast.makeText(requireContext(), "Playing: " + song.getTitle(), Toast.LENGTH_SHORT).show();
    }

    private void playSavedTrack(TelegramModels.TelegramTrack track) {
        if (downloadManager == null) return;
        String filePath = downloadManager.findExistingFile(track.fileName);
        if (filePath == null) {
            Toast.makeText(requireContext(), "File not found: " + track.fileName, Toast.LENGTH_SHORT).show();
            return;
        }

        // Find the song in MediaStore by file path
        Song song = findSongByPath(filePath);
        if (song == null) {
            Toast.makeText(requireContext(), "Song not in library: " + track.fileName, Toast.LENGTH_SHORT).show();
            return;
        }

        PlaybackManager pm = PlaybackManager.getInstance(requireContext());
        pm.bind();
        pm.play(Collections.singletonList(song), 0);
        Toast.makeText(requireContext(), "Playing: " + track.getDisplayName(), Toast.LENGTH_SHORT).show();
    }

    private Song findSongByPath(String filePath) {
        try {
            String[] projection = {
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.ALBUM,
                    MediaStore.Audio.Media.ALBUM_ID,
                    MediaStore.Audio.Media.DURATION,
                    MediaStore.Audio.Media.DATE_ADDED,
                    MediaStore.Audio.Media.DATA
            };
            String selection = MediaStore.Audio.Media.DATA + "=?";
            String[] selectionArgs = new String[]{filePath};

            try (android.database.Cursor cursor = requireContext().getContentResolver().query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    projection, selection, selectionArgs, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    long id = cursor.getLong(0);
                    String title = cursor.getString(1);
                    String artist = cursor.getString(2);
                    String album = cursor.getString(3);
                    long albumId = cursor.getLong(4);
                    long duration = cursor.getLong(5);
                    long dateAdded = cursor.getLong(6);
                    Uri contentUri = ContentUris.withAppendedId(
                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id);
                    return new Song(id, title, artist, album, albumId, duration, dateAdded, contentUri);
                }
            }
        } catch (Exception e) {
            Log.w("TelegramFragment", "findSongByPath failed", e);
        }
        return null;
    }

    // ── Download ────────────────────────────────────────────

    private void startDownload(TelegramModels.TelegramTrack track, TelegramListAdapter.VH holder) {
        if (downloadManager == null || client == null) {
            Toast.makeText(requireContext(), "Configure bot token in Settings first", Toast.LENGTH_SHORT).show();
            return;
        }

        // Show progress
        holder.action.setText("...");
        holder.action.setEnabled(false);
        holder.progress.setVisibility(View.VISIBLE);
        holder.progressText.setVisibility(View.VISIBLE);
        holder.progress.setIndeterminate(true);

        client.getFilePath(track.fileId,
                fileResult -> {
                    if (fileResult == null) {
                        holder.action.setText("ERROR");
                        holder.action.setEnabled(true);
                        holder.progress.setVisibility(View.GONE);
                        holder.progressText.setVisibility(View.GONE);
                        return;
                    }

                    String filePath = fileResult.optString("file_path", "");
                    if (filePath.isEmpty()) {
                        holder.action.setText("ERROR");
                        holder.action.setEnabled(true);
                        holder.progress.setVisibility(View.GONE);
                        holder.progressText.setVisibility(View.GONE);
                        return;
                    }

                    // Check file size limit
                    int fileSizeFromApi = fileResult.optInt("file_size", 0);
                    if (client.isOfficialApi() && fileSizeFromApi > 20 * 1024 * 1024) {
                        holder.action.setText("LARGE");
                        holder.action.setEnabled(true);
                        holder.progress.setVisibility(View.GONE);
                        holder.progressText.setVisibility(View.GONE);
                        Toast.makeText(requireContext(),
                                "File > 20MB. Use a Local Bot API server for large files.",
                                Toast.LENGTH_LONG).show();
                        return;
                    }

                    holder.progress.setIndeterminate(false);
                    holder.progress.setMax(100);

                    downloadManager.downloadFile(filePath, track.fileName, track.fileSize,
                            new TelegramDownloadManager.DownloadListener() {
                                @Override
                                public void onProgress(long bytesRead, long totalBytes) {
                                    if (totalBytes > 0) {
                                        int pct = (int) (bytesRead * 100 / totalBytes);
                                        holder.progress.setProgress(pct);
                                        holder.progressText.setText(pct + "%");
                                        holder.action.setText(pct + "%");
                                    }
                                }

                                @Override
                                public void onComplete(String savedPath) {
                                    holder.action.setText("SAVED");
                                    holder.action.setEnabled(false);
                                    holder.action.setOnClickListener(null);
                                    holder.progress.setVisibility(View.GONE);
                                    holder.progressText.setVisibility(View.GONE);
                                    localSongs = MusicRepository.loadAllSongs(requireContext());
                                    persistState();
                                                                Toast.makeText(requireContext(),
                                            "Saved: " + track.fileName, Toast.LENGTH_SHORT).show();
                                }

                                @Override
                                public void onError(String message) {
                                    holder.action.setText("ERROR");
                                    holder.action.setEnabled(true);
                                    holder.progress.setVisibility(View.GONE);
                                    holder.progressText.setVisibility(View.GONE);
                                    Toast.makeText(requireContext(),
                                            "Download failed: " + message, Toast.LENGTH_SHORT).show();
                                }
                            });
                },
                error -> {
                    holder.action.setText("ERROR");
                    holder.action.setEnabled(true);
                    holder.progress.setVisibility(View.GONE);
                    holder.progressText.setVisibility(View.GONE);
                    Toast.makeText(requireContext(), "getFile failed: " + error, Toast.LENGTH_SHORT).show();
                });
    }
}
