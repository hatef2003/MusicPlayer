package com.example.musicplayer;

import androidx.annotation.NonNull;
import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageManager;
import android.media.MediaMetadataRetriever;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognizerIntent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.mediarouter.app.MediaRouteButton;
import androidx.mediarouter.media.MediaControlIntent;
import androidx.mediarouter.media.MediaRouteSelector;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.example.musicplayer.model.Song;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class NowPlayingActivity extends BaseActivity {

    private static final int SPEECH_REQUEST = 1001;
    private static final int WRITE_PERM_REQ = 1002;
    private static final int WRITE_REQ_INTENT = 1003;

    // Top bar
    private ImageView backBtn;
    private MediaRouteButton mediaRouteButton;

    // Song info
    private ImageView albumArtView;
    private TextView titleView, artistView, albumView, metaView, hackerTrack, hackerStatus;

    // Progress
    private TextView currentTime, totalTime;
    private SeekBar seekBar;

    // Tabs
    private TextView tabLyrics, tabInfo, tabQueue;
    private View lyricsPanel, infoPanel, queuePanel;
    private TextView infoContent;

    // Queue views
    private RecyclerView queueRecycler;
    private QueueAdapter queueAdapter;
    private ItemTouchHelper queueTouchHelper;

    // Lyrics views
    private LinearLayout lyricsHeader;
    private TextView lyricsLabel;
    private TextView refreshBtn;
    private TextView saveBtn;
    private TextView fetchBtn;
    private TextView syncToggle;
    private RecyclerView lyricsRecycler;
    private SyncedLyricsAdapter lyricsAdapter;
    private LinearLayoutManager lyricsLayoutManager;
    private LinearLayout noLyricsLayout;
    private LinearLayout speechBtn;

    // Playback controls
    private ImageView playPauseBtn, prevBtn, nextBtn;

    private Song currentSong;
    private List<LyricLine> currentLyrics = new ArrayList<>();
    private boolean savingInProgress = false;
    private float density;
    private int currentTab = 0; // 0=Lyrics, 1=Info, 2=Queue

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean userDragging = false;

    private final Runnable updateRunnable = new Runnable() {
        @Override public void run() {
            PlaybackManager pm = PlaybackManager.getInstance(NowPlayingActivity.this);
            if (pm.isPlaying() && !userDragging) {
                int pos = pm.getPosition();
                int dur = pm.getDuration();
                if (dur > 0) {
                    seekBar.setMax(dur);
                    seekBar.setProgress(pos);
                    currentTime.setText(formatTime(pos));
                    totalTime.setText(formatTime(dur));
                }

                // Auto-highlight current lyrics line and scroll it to center
                if (lyricsAdapter.isSynced() && currentTab == 0) {
                    int lineIdx = lyricsAdapter.updateCurrentLine(pos);
                    if (lineIdx >= 0) {
                        int recyclerHeight = lyricsRecycler.getHeight();
                        int centerOffset = recyclerHeight / 2 - (int)(32 * density);
                        lyricsLayoutManager.scrollToPositionWithOffset(lineIdx, centerOffset);
                    }
                }
            }
            handler.postDelayed(this, 500);
        }
    };

    private final PlaybackManager.Callback callback = (isPlaying, cs, position, duration) ->
        runOnUiThread(() -> { if (cs != null) updateSongDisplay(cs, isPlaying, position, duration); });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_now_playing_v2);
        density = getResources().getDisplayMetrics().density;

        // Top bar
        backBtn = findViewById(R.id.npBackBtn);
        mediaRouteButton = findViewById(R.id.mediaRouteButton);

        // Song info
        albumArtView = findViewById(R.id.npAlbumArt);
        titleView = findViewById(R.id.npTitle);
        artistView = findViewById(R.id.npArtist);
        albumView = findViewById(R.id.npAlbum);
        metaView = findViewById(R.id.npMeta);
        hackerTrack = findViewById(R.id.npHackerTrack);
        hackerStatus = findViewById(R.id.npHackerStatus);

        // Progress
        currentTime = findViewById(R.id.npCurrentTime);
        totalTime = findViewById(R.id.npTotalTime);
        seekBar = findViewById(R.id.npSeekBar);

        // Tabs
        tabLyrics = findViewById(R.id.npTabLyrics);
        tabInfo = findViewById(R.id.npTabInfo);
        tabQueue = findViewById(R.id.npTabQueue);
        lyricsPanel = findViewById(R.id.npLyricsPanel);
        infoPanel = findViewById(R.id.npInfoPanel);
        queuePanel = findViewById(R.id.npQueuePanel);
        infoContent = findViewById(R.id.npInfoContent);

        // Lyrics
        lyricsHeader = findViewById(R.id.npLyricsHeader);
        lyricsLabel = findViewById(R.id.npLyricsLabel);
        refreshBtn = findViewById(R.id.npRefreshBtn);
        saveBtn = findViewById(R.id.npSaveBtn);
        fetchBtn = findViewById(R.id.npFetchBtn);
        syncToggle = findViewById(R.id.npSyncToggle);
        lyricsRecycler = findViewById(R.id.npLyricsRecycler);
        noLyricsLayout = findViewById(R.id.npNoLyricsLayout);
        speechBtn = findViewById(R.id.npSpeechBtn);

        // Playback controls
        playPauseBtn = findViewById(R.id.npPlayPause);
        prevBtn = findViewById(R.id.npPrev);
        nextBtn = findViewById(R.id.npNext);

        // Set up synced lyrics RecyclerView
        lyricsAdapter = new SyncedLyricsAdapter();
        lyricsLayoutManager = new LinearLayoutManager(this);
        lyricsRecycler.setLayoutManager(lyricsLayoutManager);
        lyricsRecycler.setAdapter(lyricsAdapter);

        // Set up queue RecyclerView
        queueRecycler = findViewById(R.id.npQueueRecycler);
        queueAdapter = new QueueAdapter();
        queueRecycler.setLayoutManager(new LinearLayoutManager(this));
        queueRecycler.setAdapter(queueAdapter);

        ItemTouchHelper.Callback queueCallback = new ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {
            @Override
            public boolean onMove(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh,
                                  @NonNull RecyclerView.ViewHolder target) {
                queueAdapter.onItemMove(vh.getAdapterPosition(), target.getAdapterPosition());
                return true;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int dir) { }

            @Override
            public void clearView(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh) {
                super.clearView(rv, vh);
                // Persist the reorder to the service
                PlaybackManager pm = PlaybackManager.getInstance(NowPlayingActivity.this);
                List<Song> reordered = queueAdapter.getSongs();
                int newCurrentIndex = -1;
                Song currentSong = pm.getCurrentSong();
                if (currentSong != null) {
                    for (int i = 0; i < reordered.size(); i++) {
                        if (reordered.get(i).getId() == currentSong.getId()) {
                            newCurrentIndex = i;
                            break;
                        }
                    }
                }
                // Reset queue in service with the new order
                if (!reordered.isEmpty()) {
                    pm.play(reordered, newCurrentIndex >= 0 ? newCurrentIndex : 0);
                    if (!pm.isPlaying()) pm.pause(); // don't restart playback, just set queue
                }
                queueAdapter.setCurrentIndex(newCurrentIndex >= 0 ? newCurrentIndex : pm.getCurrentIndex());
            }

            @Override
            public boolean isLongPressDragEnabled() {
                return false; // We use the drag handle instead
            }
        };
        queueTouchHelper = new ItemTouchHelper(queueCallback);
        queueTouchHelper.attachToRecyclerView(queueRecycler);
        queueAdapter.setTouchHelper(queueTouchHelper);

        queueAdapter.setOnQueueActionListener(new QueueAdapter.OnQueueActionListener() {
            @Override public void onSongClick(int position) {
                PlaybackManager.getInstance(NowPlayingActivity.this).playFromQueue(position);
            }
            @Override public void onSongRemoved(int position) {
                // Remove is visual-only; the service queue is rebuilt on clearView
            }
            @Override public void onItemMoved(int from, int to) { }
        });

        // Lyrics colors: hardcoded green for highlighted line (always visible)
        int highlightColor = ContextCompat.getColor(this, R.color.terminal_green);
        int normalColor = ContextCompat.getColor(this, R.color.text_primary);
        int pastColor = ContextCompat.getColor(this, R.color.text_secondary);
        lyricsAdapter.setColors(highlightColor, normalColor, pastColor);

        lyricsAdapter.setOnLineTapListener(timestampMs -> {
            PlaybackManager pm = PlaybackManager.getInstance(this);
            pm.seekTo((int) timestampMs);
        });

        // Back button
        backBtn.setOnClickListener(v -> finish());

        // Album art click → toggle lyrics (legacy behavior, now just switches to lyrics tab)
        albumArtView.setOnClickListener(v -> switchTab(0));

        // Tab clicks
        tabLyrics.setOnClickListener(v -> switchTab(0));
        tabInfo.setOnClickListener(v -> switchTab(1));
        tabQueue.setOnClickListener(v -> switchTab(2));

        // Action buttons
        refreshBtn.setOnClickListener(v -> refreshLyrics());
        saveBtn.setOnClickListener(v -> saveLyricsToFile());
        fetchBtn.setOnClickListener(v -> refreshLyrics());
        syncToggle.setOnClickListener(v -> toggleSync());
        speechBtn.setOnClickListener(v -> startSpeechRecognition());

        mediaRouteButton.setRouteSelector(new MediaRouteSelector.Builder()
                .addControlCategory(MediaControlIntent.CATEGORY_LIVE_AUDIO)
                .addControlCategory(MediaControlIntent.CATEGORY_LIVE_VIDEO).build());

        PlaybackManager pm = PlaybackManager.getInstance(this);

        playPauseBtn.setOnClickListener(v -> {
            if (pm.isPlaying()) pm.pause(); else pm.resume();
        });
        prevBtn.setOnClickListener(v -> pm.prev());
        nextBtn.setOnClickListener(v -> pm.next());

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (fromUser) currentTime.setText(formatTime(p));
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { userDragging = true; }
            @Override public void onStopTrackingTouch(SeekBar sb) {
                userDragging = false; pm.seekTo(sb.getProgress());
            }
        });

        Song current = pm.getCurrentSong();
        if (current != null) updateSongDisplay(current, pm.isPlaying(), pm.getPosition(), pm.getDuration());
    }

    // ═══ TAB SWITCHING ═══

    private void switchTab(int tab) {
        currentTab = tab;

        // Reset all tab text colors
        tabLyrics.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        tabInfo.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        tabQueue.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        tabLyrics.setTypeface(null, android.graphics.Typeface.NORMAL);
        tabInfo.setTypeface(null, android.graphics.Typeface.NORMAL);
        tabQueue.setTypeface(null, android.graphics.Typeface.NORMAL);

        // Hide all panels
        lyricsPanel.setVisibility(View.GONE);
        infoPanel.setVisibility(View.GONE);
        queuePanel.setVisibility(View.GONE);

        switch (tab) {
            case 0: // Lyrics
                tabLyrics.setTextColor(getAccentColor());
                tabLyrics.setTypeface(null, android.graphics.Typeface.BOLD);
                lyricsPanel.setVisibility(View.VISIBLE);
                showLyricsIfLoaded();
                break;
            case 1: // Info
                tabInfo.setTextColor(getAccentColor());
                tabInfo.setTypeface(null, android.graphics.Typeface.BOLD);
                infoPanel.setVisibility(View.VISIBLE);
                showSongInfo();
                break;
            case 2: // Queue
                tabQueue.setTextColor(getAccentColor());
                tabQueue.setTypeface(null, android.graphics.Typeface.BOLD);
                queuePanel.setVisibility(View.VISIBLE);
                showQueueInfo();
                break;
        }
    }

    private void showLyricsIfLoaded() {
        if (lyricsAdapter.getItemCount() > 0) {
            lyricsHeader.setVisibility(View.VISIBLE);
            lyricsRecycler.setVisibility(View.VISIBLE);
            noLyricsLayout.setVisibility(View.GONE);
        }
    }

    private void showSongInfo() {
        if (currentSong == null) {
            infoContent.setText("// no song playing");
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Title:     ").append(currentSong.getTitle()).append("\n");
        sb.append("Artist:    ").append(currentSong.getArtist()).append("\n");
        sb.append("Album:     ").append(currentSong.getAlbum()).append("\n");
        sb.append("Duration:  ").append(currentSong.getDurationLabel()).append("\n");
        sb.append("ID:        ").append(currentSong.getId()).append("\n");
        sb.append("URI:       ").append(currentSong.getContentUri()).append("\n\n");

        // Try to get technical metadata
        try {
            MediaMetadataRetriever mmr = new MediaMetadataRetriever();
            mmr.setDataSource(this, currentSong.getContentUri());
            String mime = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE);
            String bitrate = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE);
            String sampleRate = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE);
            String channels = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_NUM_TRACKS);

            sb.append("// technical metadata\n");
            if (mime != null) sb.append("Format:    ").append(mime).append("\n");
            if (bitrate != null) sb.append("Bitrate:   ").append(Integer.parseInt(bitrate) / 1000).append(" kbps\n");
            if (sampleRate != null) sb.append("Sample:    ").append(sampleRate).append(" Hz\n");
            if (channels != null) sb.append("Channels:  ").append(channels).append("\n");
            mmr.release();
        } catch (NumberFormatException | SecurityException | java.io.IOException e) {
            sb.append("// metadata unavailable\n");
        }
        infoContent.setText(sb.toString());
    }

    private void showQueueInfo() {
        PlaybackManager pm = PlaybackManager.getInstance(this);
        queueAdapter.setQueue(pm.getQueue(), pm.getCurrentIndex());
    }

    // ═══ SONG DISPLAY ═══

    private void updateSongDisplay(Song song, boolean isPlaying, int position, int duration) {
        titleView.setText(song.getTitle());
        artistView.setText(song.getArtist());
        albumView.setText(song.getAlbum());
        playPauseBtn.setImageResource(isPlaying ? R.drawable.ic_pause : R.drawable.ic_play);
        hackerStatus.setText(isPlaying ? "PLAYING" : "PAUSED");
        loadAlbumArt(song);

        // Build metadata line
        String format = getFormatLabel(song);
        metaView.setText(format);

        if (duration > 0 && !userDragging) {
            seekBar.setMax(duration); seekBar.setProgress(position);
            currentTime.setText(formatTime(position));
            totalTime.setText(formatTime(duration));
        }
        if (currentSong == null || currentSong.getId() != song.getId()) {
            currentSong = song;
            currentLyrics.clear();
            hackerTrack.setText(String.format(Locale.US, "TRACK_%02d", PlaybackManager.getInstance(this).getCurrentIndex() + 1));
            loadLyricsForSong(song);
            if (currentTab == 1) showSongInfo();
            if (currentTab == 2) showQueueInfo();
        }
        // Update queue highlight in real-time
        if (queueAdapter != null) {
            queueAdapter.setCurrentIndex(PlaybackManager.getInstance(this).getCurrentIndex());
        }
    }

    private String getFormatLabel(Song song) {
        try {
            MediaMetadataRetriever mmr = new MediaMetadataRetriever();
            mmr.setDataSource(this, song.getContentUri());
            String mime = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE);
            String bitrate = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE);
            String sampleRate = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE);
            mmr.release();

            StringBuilder sb = new StringBuilder();
            if (mime != null) {
                if (mime.contains("mp3") || mime.contains("mpeg")) sb.append("MP3");
                else if (mime.contains("flac")) sb.append("FLAC");
                else if (mime.contains("ogg")) sb.append("OGG");
                else if (mime.contains("aac") || mime.contains("mp4")) sb.append("AAC");
                else if (mime.contains("wav")) sb.append("WAV");
                else sb.append(mime.substring(mime.lastIndexOf('/') + 1).toUpperCase());
            } else {
                sb.append("AUDIO");
            }
            if (bitrate != null) sb.append(" • ").append(Integer.parseInt(bitrate) / 1000).append("kbps");
            if (sampleRate != null) sb.append(" • ").append(Integer.parseInt(sampleRate) / 1000).append("kHz");
            return sb.toString();
        } catch (NumberFormatException | SecurityException | java.io.IOException e) {
            return "AUDIO";
        }
    }

    // ═══ LYRICS ═══

    private void loadLyricsForSong(Song song) {
        lyricsHeader.setVisibility(View.GONE);
        lyricsRecycler.setVisibility(View.GONE);
        noLyricsLayout.setVisibility(View.GONE);

        lyricsAdapter.setLines(Collections.singletonList(new LyricLine(-1, "// loading lyrics...")));
        lyricsLabel.setText("LOADING");
        if (currentTab == 0) {
            lyricsHeader.setVisibility(View.VISIBLE);
            lyricsRecycler.setVisibility(View.VISIBLE);
        }

        LyricsService.fetchLyrics(this, song, new LyricsService.LyricsCallback() {
            @Override public void onLyricsLoaded(List<LyricLine> lines) {
                runOnUiThread(() -> {
                    if (currentSong == null || currentSong.getId() != song.getId()) return;
                    applyLyrics(lines);
                });
            }
            @Override public void onError(String message) {
                runOnUiThread(() -> {
                    if (currentSong == null || currentSong.getId() != song.getId()) return;
                    lyricsAdapter.setLines(Collections.emptyList());
                    lyricsRecycler.setVisibility(View.GONE);
                    lyricsHeader.setVisibility(View.GONE);
                    if (currentTab == 0) noLyricsLayout.setVisibility(View.VISIBLE);
                });
            }
        });
    }

    private void refreshLyrics() {
        if (currentSong == null) return;
        switchTab(0);

        lyricsAdapter.setLines(Collections.singletonList(new LyricLine(-1, "// fetching from network...")));
        lyricsHeader.setVisibility(View.VISIBLE);
        lyricsRecycler.setVisibility(View.VISIBLE);
        noLyricsLayout.setVisibility(View.GONE);
        Toast.makeText(this, "Fetching lyrics...", Toast.LENGTH_SHORT).show();

        LyricsService.fetchFresh(this, currentSong, new LyricsService.LyricsCallback() {
            @Override public void onLyricsLoaded(List<LyricLine> lines) {
                runOnUiThread(() -> {
                    if (currentSong == null) return;
                    applyLyrics(lines);
                    Toast.makeText(NowPlayingActivity.this,
                            lines.size() + " lines loaded", Toast.LENGTH_SHORT).show();
                });
            }
            @Override public void onError(String msg) {
                runOnUiThread(() -> {
                    lyricsAdapter.setLines(Collections.emptyList());
                    lyricsRecycler.setVisibility(View.GONE);
                    lyricsHeader.setVisibility(View.GONE);
                    noLyricsLayout.setVisibility(View.VISIBLE);
                    Toast.makeText(NowPlayingActivity.this, "No lyrics found", Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void toggleSync() {
        boolean newEnabled = !lyricsAdapter.isSyncEnabled();
        lyricsAdapter.setSyncEnabled(newEnabled);
        syncToggle.setText(newEnabled ? "SYNC" : "NOSYNC");
        syncToggle.setTextColor(newEnabled ? getAccentColor() : ContextCompat.getColor(this, R.color.text_secondary));
        Toast.makeText(this, newEnabled ? "Sync enabled" : "Sync disabled — showing plain lyrics", Toast.LENGTH_SHORT).show();
    }

    private void applyLyrics(List<LyricLine> lines) {
        currentLyrics = lines;
        lyricsAdapter.setLines(lines);
        if (lyricsAdapter.hasSyncData()) {
            lyricsLabel.setText(lyricsAdapter.isSyncEnabled() ? "SYNCED" : "SYNCED (off)");
            syncToggle.setVisibility(View.VISIBLE);
            syncToggle.setText(lyricsAdapter.isSyncEnabled() ? "SYNC" : "NOSYNC");
            syncToggle.setTextColor(lyricsAdapter.isSyncEnabled() ? getAccentColor() : ContextCompat.getColor(this, R.color.text_secondary));
            syncToggle.setVisibility(View.VISIBLE);
        } else {
            lyricsLabel.setText("LYRICS");
            syncToggle.setVisibility(View.GONE);
        }
        lyricsRecycler.scrollToPosition(0);
        noLyricsLayout.setVisibility(View.GONE);
        if (currentTab == 0) {
            lyricsHeader.setVisibility(View.VISIBLE);
            lyricsRecycler.setVisibility(View.VISIBLE);
        } else {
            lyricsHeader.setVisibility(View.GONE);
            lyricsRecycler.setVisibility(View.GONE);
        }
    }

    // ═══ SAVE LYRICS ═══

    private void saveLyricsToFile() {
        if (currentSong == null || savingInProgress) return;

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, WRITE_PERM_REQ);
                return;
            }
        }

        final String text = buildPlainText();
        if (text.isEmpty()) {
            Toast.makeText(this, "No lyrics to save", Toast.LENGTH_SHORT).show();
            return;
        }

        final android.net.Uri songUri = currentSong.getContentUri();
        final String songArtist = currentSong.getArtist();
        final String songTitle = currentSong.getTitle();

        savingInProgress = true;
        saveBtn.setEnabled(false);
        saveBtn.setAlpha(0.4f);
        Toast.makeText(this, "Embedding lyrics...", Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            LyricsEmbedder.EmbedResult result = LyricsEmbedder.embedLyrics(
                    this, songUri, songArtist, songTitle, text);
            runOnUiThread(() -> {
                savingInProgress = false;
                saveBtn.setEnabled(true);
                saveBtn.setAlpha(1.0f);
                if (result.success) {
                    Toast.makeText(this, "✅ Lyrics embedded", Toast.LENGTH_LONG).show();
                } else if (result.intentSender != null) {
                    try {
                        startIntentSenderForResult(result.intentSender, WRITE_REQ_INTENT, null, 0, 0, 0);
                    } catch (IntentSender.SendIntentException e) {
                        Toast.makeText(this, "❌ Permission error", Toast.LENGTH_SHORT).show();
                    }
                } else {
                    Toast.makeText(this, "❌ " + result.message, Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    private String buildPlainText() {
        StringBuilder sb = new StringBuilder();
        for (LyricLine ll : currentLyrics) {
            if (ll.text.startsWith("//")) continue;
            if (sb.length() > 0) sb.append("\n");
            sb.append(ll.text);
        }
        return sb.toString();
    }

    // ═══ SPEECH ═══

    private void startSpeechRecognition() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak the lyrics...");
        try { startActivityForResult(intent, SPEECH_REQUEST); }
        catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Speech recognition not available", Toast.LENGTH_LONG).show();
        }
    }

    // ═══ PERMISSIONS ═══

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == WRITE_PERM_REQ && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            saveLyricsToFile();
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == WRITE_REQ_INTENT && resultCode == RESULT_OK) {
            saveLyricsToFile();
        }
        if (requestCode == SPEECH_REQUEST && resultCode == RESULT_OK && data != null) {
            ArrayList<String> results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (results != null && !results.isEmpty()) {
                String spoken = results.get(0);
                currentLyrics = Collections.singletonList(new LyricLine(-1, spoken));
                lyricsAdapter.setLines(currentLyrics);
                lyricsLabel.setText("SPEECH");
                switchTab(0);
            }
        }
    }

    // ═══ HELPERS ═══

    private void loadAlbumArt(Song song) {
        Glide.with(this).load(MusicRepository.getAlbumArtUri(song.getAlbumId()))
                .placeholder(R.drawable.ic_album).error(R.drawable.ic_album)
                .centerCrop().into(albumArtView);
    }

    /** Get the accent color based on current settings (hardcoded lookup) */
    private int getAccentColor() {
        return ContextCompat.getColor(this, R.color.terminal_green);
    }

    private String formatTime(int msec) {
        int s = msec / 1000;
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    @Override protected void onStart() {
        super.onStart();
        PlaybackManager.getInstance(this).bind();
        PlaybackManager.getInstance(this).addCallback(callback);
        handler.post(updateRunnable);
    }

    @Override protected void onStop() {
        super.onStop();
        handler.removeCallbacks(updateRunnable);
        PlaybackManager.getInstance(this).removeCallback(callback);
    }
}
