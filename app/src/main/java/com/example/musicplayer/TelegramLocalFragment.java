package com.example.musicplayer;

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.musicplayer.model.Song;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import com.example.musicplayer.model.Song;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Fragment that scans Telegram's local cache directories and displays
 * music files found there.  These are files downloaded by the official
 * Telegram app that haven't been saved into this music player's library.
 *
 * Playback strategy:
 *   1. If file is already in MediaStore (found via broad scan) → play via content URI
 *   2. Otherwise → copy file to our Music/AndroidPlayer/ dir → register in MediaStore → play
 */
public class TelegramLocalFragment extends Fragment {

    // ── UI ──────────────────────────────────────────────────
    private RecyclerView list;
    private TextView emptyText;
    private TextView statusText;
    private TextView refreshBtn;
    private android.widget.ProgressBar progressBar;
    private TelegramLocalAdapter adapter;

    // ── Data ────────────────────────────────────────────────
    private TelegramLocalScanManager scanManager;
    private List<TelegramLocalScanManager.TelegramLocalFile> localFiles = new ArrayList<>();
    private boolean isScanning = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRetainInstance(true);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_telegram_local, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        list = view.findViewById(R.id.tgLocalList);
        emptyText = view.findViewById(R.id.tgLocalEmpty);
        statusText = view.findViewById(R.id.tgLocalStatus);
        refreshBtn = view.findViewById(R.id.tgLocalRefreshBtn);
        progressBar = view.findViewById(R.id.tgLocalProgressBar);

        scanManager = new TelegramLocalScanManager(requireContext());

        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        final int baseBottomPadding = (int) (32 * getResources().getDisplayMetrics().density);
        ViewCompat.setOnApplyWindowInsetsListener(list, (v, insets) -> {
            int navigationBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(),
                    baseBottomPadding + navigationBottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(list);
        adapter = new TelegramLocalAdapter();
        list.setAdapter(adapter);

        refreshBtn.setOnClickListener(v -> onRefresh());

        // Initial scan
        onRefresh();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (!isScanning) onRefresh();
    }

    // ── Refresh / Scan ──────────────────────────────────────

    private void onRefresh() {
        if (isScanning) return;
        isScanning = true;
        showProgress(true);
        statusText.setText("scanning telegram cache...");

        new Thread(() -> {
            List<TelegramLocalScanManager.TelegramLocalFile> files = scanManager.scan();
            localFiles = files;
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    isScanning = false;
                    showProgress(false);
                    refreshDisplay();
                });
            } else {
                isScanning = false;
                showProgress(false);
            }
        }).start();
    }

    // ── Display ─────────────────────────────────────────────

    private void refreshDisplay() {
        if (localFiles.isEmpty()) {
            statusText.setText("telegram-local (0 files)");
            emptyText.setText(
                    "No Telegram cache files found.\n\n" +
                    "Files downloaded in Telegram's official app\n" +
                    "are stored in:\n" +
                    "  Android/data/org.telegram.messenger/files/\n\n" +
                    "Make sure:\n" +
                    "• You've downloaded music in Telegram\n" +
                    "• Your device allows access to app data dirs\n" +
                    "• Try refreshing");
            emptyText.setVisibility(View.VISIBLE);
            list.setVisibility(View.GONE);
        } else {
            statusText.setText("telegram-local (" + localFiles.size() + " files)");
            emptyText.setVisibility(View.GONE);
            list.setVisibility(View.VISIBLE);
        }
        adapter.notifyDataSetChanged();
    }

    private void showProgress(boolean show) {
        progressBar.setVisibility(show ? View.VISIBLE : View.GONE);
        refreshBtn.setEnabled(!show);
    }

    // ── Playback ────────────────────────────────────────────

    /**
     * Try to find a MediaStore content URI for the given file path.
     * Returns the content URI if found, null otherwise.
     */
    private Uri findMediaStoreUriForPath(String filePath) {
        String[] projection = new String[]{MediaStore.Audio.Media._ID};
        String selection = MediaStore.Audio.Media.DATA + "=?";
        String[] selectionArgs = new String[]{filePath};
        try (android.database.Cursor c = requireContext().getContentResolver().query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection, selection, selectionArgs, null)) {
            if (c != null && c.moveToFirst()) {
                long id = c.getLong(0);
                return ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id);
            }
        } catch (Exception e) {
            Log.w("TelegramLocalFragment", "MS URI lookup failed", e);
        }
        return null;
    }

    /**
     * Copy a file from a potentially-inaccessible location to our app's
     * Music/AndroidPlayer/ directory, then return the new path.
     */
    private String copyToOurMusicDir(TelegramLocalScanManager.TelegramLocalFile file) {
        File musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC);
        Log.i("TGLocalPlay", "  Music dir: " + musicDir + " exists: " + musicDir.exists());
        File ourDir = new File(musicDir, "AndroidPlayer");
        Log.i("TGLocalPlay", "  Our dir: " + ourDir + " exists: " + ourDir.exists());
        if (!ourDir.exists() && !ourDir.mkdirs()) {
            Log.e("TGLocalPlay", "Cannot create AndroidPlayer dir");
            return null;
        }
        // Use a unique name to avoid collisions
        String destName = "tg_" + file.name;
        File destFile = new File(ourDir, destName);
        // If already exists, skip copy
        if (destFile.exists()) return destFile.getAbsolutePath();

        try (InputStream in = new FileInputStream(new File(file.path));
             FileOutputStream out = new FileOutputStream(destFile)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
        } catch (Exception e) {
            Log.e("TelegramLocalFragment", "Copy failed for " + file.path, e);
            return null;
        }
        return destFile.getAbsolutePath();
    }

    private void playLocalFile(TelegramLocalScanManager.TelegramLocalFile file) {
        Log.i("TGLocalPlay", "=== playLocalFile: " + file.path);
        Log.i("TGLocalPlay", "  exists: " + new File(file.path).exists()
                + " readable: " + new File(file.path).canRead()
                + " size: " + file.size);

        // Strategy 1: Check if already in our library via MediaStore
        List<Song> allSongs = MusicRepository.loadAllSongs(requireContext());
        for (Song song : allSongs) {
            try {
                android.database.Cursor c = requireContext().getContentResolver().query(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        new String[]{MediaStore.Audio.Media.DATA},
                        MediaStore.Audio.Media.DATA + "=?",
                        new String[]{file.path},
                        null);
                if (c != null) {
                    if (c.moveToFirst()) {
                        c.close();
                        Log.i("TGLocalPlay", "  -> Already in library, playing");
                        PlaybackManager pm = PlaybackManager.getInstance(requireContext());
                        pm.bind();
                        pm.play(allSongs, allSongs.indexOf(song));
                        Toast.makeText(requireContext(),
                                "Playing: " + file.getDisplayName(), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    c.close();
                }
            } catch (Exception e) {
                Log.w("TGLocalPlay", "Strategy1 MS check failed", e);
            }
        }
        Log.i("TGLocalPlay", "  Strategy 1: not in library");

        // Strategy 2: Check if MediaStore has a content URI for this file
        Uri contentUri = findMediaStoreUriForPath(file.path);
        if (contentUri != null) {
            Log.i("TGLocalPlay", "  Strategy 2: Found MS URI: " + contentUri);
            try {
                long songId = ContentUris.parseId(contentUri);
                Song tempSong = new Song(songId, file.getTitle(), file.getArtist(),
                        "Telegram Cache", -1, file.durationMs, file.dateAdded, contentUri);
                PlaybackManager pm = PlaybackManager.getInstance(requireContext());
                pm.bind();
                pm.setQueueSilent(java.util.Collections.singletonList(tempSong), 0);
                pm.playFromQueue(0);
                Toast.makeText(requireContext(),
                        "Playing: " + file.getDisplayName(), Toast.LENGTH_SHORT).show();
                Log.i("TGLocalPlay", "  -> Playing via MS URI");
            } catch (Exception e) {
                Log.w("TGLocalPlay", "Strategy 2 MS URI playback failed", e);
                playCopied(file);
            }
            return;
        }
        Log.i("TGLocalPlay", "  Strategy 2: No MS URI found");

        // Strategy 3: Copy to our accessible dir then play
        playCopied(file);
    }

    private void playCopied(TelegramLocalScanManager.TelegramLocalFile file) {
        Log.i("TGLocalPlay", "  Strategy 3: attempting copy to AndroidPlayer");
        new Thread(() -> {
            Log.i("TGLocalPlay", "  Copying: " + file.path);
            String destPath = copyToOurMusicDir(file);
            Log.i("TGLocalPlay", "  Copy result: " + (destPath != null ? destPath : "FAILED"));
            if (destPath == null) {
                mainHandler.post(() ->
                        Toast.makeText(requireContext(),
                                "Cannot access file: " + file.name + "\nTry granting storage access in Settings",
                                Toast.LENGTH_LONG).show());
                return;
            }

            // Register the copied file in MediaStore
            try {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Audio.Media.DISPLAY_NAME, file.name);
                values.put(MediaStore.Audio.Media.MIME_TYPE, guessMimeType(file.name));
                values.put(MediaStore.Audio.Media.TITLE, file.getTitle());
                values.put(MediaStore.Audio.Media.DATE_ADDED, file.dateAdded / 1000);
                values.put(MediaStore.Audio.Media.IS_MUSIC, 1);
                if (Build.VERSION.SDK_INT >= 29) {
                    values.put(MediaStore.Audio.Media.RELATIVE_PATH,
                            Environment.DIRECTORY_MUSIC + "/AndroidPlayer");
                }

                Uri uri = requireContext().getContentResolver().insert(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values);

                if (uri != null && Build.VERSION.SDK_INT >= 29) {
                    try (InputStream in = new FileInputStream(new File(destPath));
                         OutputStream out = requireContext().getContentResolver().openOutputStream(uri)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) != -1) {
                            out.write(buf, 0, n);
                        }
                    }
                    // Delete temp copy since MediaStore now owns the content
                    new File(destPath).delete();
                    destPath = null;
                }

                long songId = (uri != null) ? ContentUris.parseId(uri) : -1;
                Uri playUri = (uri != null) ? uri : Uri.fromFile(new File(destPath));
                Song tempSong = new Song(songId, file.getTitle(), file.getArtist(),
                        "Telegram Cache", -1, file.durationMs, file.dateAdded, playUri);

                mainHandler.post(() -> {
                    PlaybackManager pm = PlaybackManager.getInstance(requireContext());
                    pm.bind();
                    // Use setQueueSilent to set the queue, then explicitly play from index 0
                    // This avoids the MediaPlayer.prepareAsync() race that can cause
                    // the wrong song to play when rapidly tapping different tracks.
                    pm.setQueueSilent(java.util.Collections.singletonList(tempSong), 0);
                    pm.playFromQueue(0);
                    Toast.makeText(requireContext(),
                            "Playing: " + file.getDisplayName(), Toast.LENGTH_SHORT).show();
                });

            } catch (Exception e) {
                Log.w("TelegramLocalFragment", "MS insert after copy failed", e);
                // Fallback: play directly from the copied file
                final String finalDestPath = destPath;
                mainHandler.post(() -> playViaFile(new File(finalDestPath), file));
            }
        }).start();
    }

    private void playViaFile(File file, TelegramLocalScanManager.TelegramLocalFile srcInfo) {
        try {
            if (!file.exists()) {
                Toast.makeText(requireContext(), "File not found", Toast.LENGTH_SHORT).show();
                return;
            }
            MediaPlayer mp = new MediaPlayer();
            mp.setDataSource(file.getAbsolutePath());
            mp.prepare();
            mp.start();
            Toast.makeText(requireContext(), "Playing: " + srcInfo.getDisplayName(),
                    Toast.LENGTH_SHORT).show();
            Log.i("TelegramLocalFragment", "Playing via direct MP: " + file.getAbsolutePath());
        } catch (Exception e) {
            Log.e("TelegramLocalFragment", "Direct playback failed", e);
            Toast.makeText(requireContext(),
                    "Cannot play: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }



    private String guessMimeType(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".flac")) return "audio/flac";
        if (lower.endsWith(".wav")) return "audio/wav";
        if (lower.endsWith(".ogg")) return "audio/ogg";
        if (lower.endsWith(".m4a")) return "audio/mp4";
        if (lower.endsWith(".aac")) return "audio/aac";
        if (lower.endsWith(".wma")) return "audio/x-ms-wma";
        if (lower.endsWith(".opus")) return "audio/opus";
        if (lower.endsWith(".aiff") || lower.endsWith(".aif")) return "audio/aiff";
        return "audio/mpeg";
    }

    // ── Adapter ─────────────────────────────────────────────

    private class TelegramLocalAdapter extends RecyclerView.Adapter<TelegramLocalAdapter.VH> {

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_telegram, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            TelegramLocalScanManager.TelegramLocalFile file = localFiles.get(position);
            holder.progress.setVisibility(View.GONE);
            holder.progressText.setVisibility(View.GONE);

            holder.index.setText(String.format(java.util.Locale.US, "%02d", position + 1));
            holder.title.setText(file.getDisplayName());
            holder.subtitle.setText(
                    (file.getDurationLabel().isEmpty() ? "" : file.getDurationLabel() + " • ") +
                    file.getFileSizeLabel());

            holder.action.setText("PLAY");
            holder.action.setOnClickListener(v -> playLocalFile(file));
            holder.action.setTextColor(holder.itemView.getContext()
                    .getResources().getColor(R.color.terminal_green));
            holder.title.setTextColor(holder.itemView.getContext()
                    .getResources().getColor(R.color.text_primary));
        }

        @Override
        public int getItemCount() {
            return localFiles.size();
        }

        class VH extends RecyclerView.ViewHolder {
            TextView index, title, subtitle, action, progressText;
            android.widget.ProgressBar progress;

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
}
