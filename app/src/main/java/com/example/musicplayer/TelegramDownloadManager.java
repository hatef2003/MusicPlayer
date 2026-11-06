package com.example.musicplayer;

import android.content.ContentValues;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Handles downloading Telegram audio files to the device's Music/AndroidPlayer directory.
 * Registers files in MediaStore so the music scanner picks them up automatically.
 * Skips files that already exist (same filename + size).
 */
public class TelegramDownloadManager {

    private static final String TAG = "TelegramDLManager";
    private static final String DOWNLOAD_DIR = "AndroidPlayer";

    public interface DownloadListener {
        void onProgress(long bytesRead, long totalBytes);
        void onComplete(String filePath);
        void onError(String message);
    }

    private final TelegramBotClient client;
    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public TelegramDownloadManager(Context context, TelegramBotClient client) {
        this.appContext = context.getApplicationContext();
        this.client = client;
    }

    private void postMain(Runnable r) {
        mainHandler.post(r);
    }

    /**
     * Check if a track with the given filename already exists (ignoring size).
     */
    public boolean isAlreadyDownloaded(String fileName, long fileSize) {
        return findExistingFile(fileName) != null;
    }

    /**
     * Find an existing file matching the filename (any size).
     * Returns the absolute path or null.
     */
    public String findExistingFile(String fileName) {
        // Check MediaStore first
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                String selection = MediaStore.Audio.Media.DISPLAY_NAME + "=? AND " +
                        MediaStore.Audio.Media.RELATIVE_PATH + " LIKE ?";
                String[] selectionArgs = new String[]{fileName, "%" + DOWNLOAD_DIR + "%"};
                try (android.database.Cursor cursor = appContext
                        .getContentResolver().query(
                                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                                new String[]{MediaStore.Audio.Media.DATA},
                                selection, selectionArgs, null)) {
                    if (cursor != null && cursor.moveToFirst()) {
                        String path = cursor.getString(0);
                        if (path != null && new File(path).exists()) return path;
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "MediaStore query failed", e);
            }
        }

        // Fallback: check common download paths
        File[] dirs = {
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        };
        for (File dir : dirs) {
            if (dir == null) continue;
            File target = new File(dir, DOWNLOAD_DIR);
            File file = new File(target, fileName);
            if (file.exists()) return file.getAbsolutePath();
        }
        return null;
    }

    /**
     * Download a Telegram file by its file_path.
     * Calls listener on progress/complete/error on the main thread.
     */
    public void downloadFile(String filePath, String fileName, long fileSize, DownloadListener listener) {
        client.downloadFile(filePath,
                // Progress (called from background InputStream.read)
                (bytesRead, totalBytes) -> {
                    if (listener != null) postMain(() -> listener.onProgress(bytesRead, totalBytes));
                },
                // Complete — save to disk (runs on background thread)
                (inputStream) -> {
                    try {
                        String savedPath = saveToDisk(inputStream, fileName);
                        if (savedPath != null) {
                            registerInMediaStore(savedPath, fileName);
                            if (listener != null) postMain(() -> listener.onComplete(savedPath));
                        } else {
                            if (listener != null) postMain(() -> listener.onError("Failed to save file"));
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Save failed", e);
                        if (listener != null) postMain(() -> listener.onError(e.getMessage()));
                    }
                },
                // Error (already on main thread from TelegramBotClient)
                (errorMsg) -> {
                    if (listener != null) listener.onError(errorMsg);
                }
        );
    }

    private String saveToDisk(InputStream inputStream, String fileName) throws Exception {
        // Ensure directory exists
        File musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC);
        File targetDir = new File(musicDir, DOWNLOAD_DIR);
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            Log.e(TAG, "Failed to create directory: " + targetDir);
            return null;
        }

        // On API < 29 write directly; on API 29+ write a temp file that
        // registerInMediaStore() then copies in via MediaStore.
        File outFile = Build.VERSION.SDK_INT < 29
                ? new File(targetDir, fileName)
                : new File(targetDir, ".tmp_" + fileName);

        boolean ok = false;
        try (InputStream in = inputStream;
             FileOutputStream fos = new FileOutputStream(outFile)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) {
                fos.write(buf, 0, n);
            }
            ok = true;
        } finally {
            // Never leave a truncated file behind after a network error.
            if (!ok && outFile.exists() && !outFile.delete()) {
                Log.w(TAG, "Could not delete partial file " + outFile);
            }
        }
        return outFile.getAbsolutePath();
    }

    private void registerInMediaStore(String filePath, String displayName) {
        if (Build.VERSION.SDK_INT < 29) {
            // On older Android, the file is already in a scanned location
            return;
        }
        try {
            File file = new File(filePath);
            if (!file.exists()) return;

            ContentValues values = new ContentValues();
            values.put(MediaStore.Audio.Media.DISPLAY_NAME, displayName);
            values.put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg");
            values.put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/" + DOWNLOAD_DIR);
            values.put(MediaStore.Audio.Media.IS_PENDING, 1);

            android.net.Uri uri = appContext
                    .getContentResolver()
                    .insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values);

            if (uri != null) {
                try (InputStream in = new java.io.FileInputStream(file);
                     OutputStream out = appContext.getContentResolver().openOutputStream(uri)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        out.write(buf, 0, n);
                    }
                }

                values.clear();
                values.put(MediaStore.Audio.Media.IS_PENDING, 0);
                appContext.getContentResolver().update(uri, values, null, null);
            }

            // Delete temp file
            file.delete();
        } catch (Exception e) {
            Log.e(TAG, "MediaStore registration failed", e);
        }
    }
}
