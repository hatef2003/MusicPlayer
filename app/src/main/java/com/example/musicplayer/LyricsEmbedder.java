package com.example.musicplayer;

import android.app.RecoverableSecurityException;
import android.content.Context;
import android.content.IntentSender;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.util.Log;

import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Collections;

/**
 * Embeds lyrics into music files as ID3 USLT (Unsynchronised Lyrics/Text) tags.
 * Supports both direct file access (API &lt; 29) and scoped storage (API 29+).
 */
public class LyricsEmbedder {

    private static final String TAG = "LyricsEmbedder";

    /**
     * Result of an embedding attempt.
     */
    public static class EmbedResult {
        public final boolean success;
        public final String message;
        public final IntentSender intentSender;

        EmbedResult(boolean s, String m) { success = s; message = m; intentSender = null; }
        EmbedResult(boolean s, String m, IntentSender is) { success = s; message = m; intentSender = is; }
    }

    /**
     * Write lyrics into the audio file's metadata tags.
     * Works for MP3 (ID3v2 USLT) and FLAC (Vorbis LYRICS).
     */
    public static EmbedResult embedLyrics(Context context, Uri contentUri, String artist, String title, String lyrics) {
        if (lyrics == null || lyrics.isEmpty())
            return new EmbedResult(false, "Empty lyrics");

        // 1. Try direct file path (works on API < 29, and some API 29+ devices)
        String filePath = resolveFilePath(context, contentUri);
        if (filePath != null) {
            EmbedResult directResult = embedToFile(new File(filePath), title, lyrics);
            if (directResult.success) return directResult;
            // Fall through to scoped storage if direct write failed (e.g. file not writable)
        }

        // 2. Scoped storage: copy → modify → write back
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return embedViaScopedStorage(context, contentUri, title, lyrics);
        }

        return new EmbedResult(false, "Could not access file");
    }

    /** Embed lyrics when we have direct file path access. */
    private static EmbedResult embedToFile(File audioFile, String title, String lyrics) {
        try {
            if (!audioFile.exists()) return new EmbedResult(false, "File not found");
            if (!audioFile.canWrite()) return new EmbedResult(false, "File not writable");

            AudioFile af = AudioFileIO.read(audioFile);
            Tag tag = af.getTagOrCreateDefault();
            tag.setField(FieldKey.LYRICS, lyrics);
            AudioFileIO.write(af);

            Log.i(TAG, "Lyrics embedded via direct file: " + title);
            return new EmbedResult(true, "Lyrics saved to file");
        } catch (Exception e) {
            Log.e(TAG, "Failed to embed lyrics for " + title, e);
            return new EmbedResult(false, "Embed failed: " + e.getMessage());
        }
    }

    /**
     * Get the file extension for a content URI (e.g. ".mp3", ".flac").
     * Falls back to ".mp3" if unknown.
     */
    private static String getFileExtension(Context context, Uri uri) {
        try {
            String displayName = null;
            try (Cursor c = context.getContentResolver().query(uri,
                    new String[]{MediaStore.Audio.Media.DISPLAY_NAME}, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    displayName = c.getString(0);
                }
            }
            if (displayName != null && displayName.contains(".")) {
                return displayName.substring(displayName.lastIndexOf('.'));
            }
        } catch (Exception ignored) {}
        return ".mp3";
    }

    /** Embed lyrics via scoped storage: copy to temp, modify, write back. */
    private static EmbedResult embedViaScopedStorage(Context context, Uri uri, String title, String lyrics) {
        File tempFile = null;
        try {
            // Copy to temp file WITH the correct audio extension (Jaudiotagger needs it)
            String ext = getFileExtension(context, uri);
            tempFile = File.createTempFile("lyrics_embed_", ext, context.getCacheDir());
            try (InputStream in = context.getContentResolver().openInputStream(uri)) {
                if (in == null) return new EmbedResult(false, "Cannot read file");
                try (FileOutputStream out = new FileOutputStream(tempFile)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
            }

            // Modify temp file with Jaudiotagger
            AudioFile af = AudioFileIO.read(tempFile);
            Tag tag = af.getTagOrCreateDefault();
            tag.setField(FieldKey.LYRICS, lyrics);
            AudioFileIO.write(af);

            // Write modified file back via content resolver
            try {
                try (java.io.OutputStream out = context.getContentResolver().openOutputStream(uri, "wt")) {
                    if (out == null) {
                        return new EmbedResult(false, "Cannot write to file (scoped storage)");
                    }
                    try (InputStream in = new java.io.FileInputStream(tempFile)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    }
                }
            } catch (SecurityException se) {
                Log.w(TAG, "Write permission denied, requesting user consent: " + se.getMessage());
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // Android 11+: use MediaStore.createWriteRequest
                    IntentSender sender = MediaStore.createWriteRequest(
                            context.getContentResolver(), Collections.singletonList(uri)).getIntentSender();
                    return new EmbedResult(false, "Permission required", sender);
                } else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
                    // Android 10: RecoverableSecurityException
                    if (se instanceof RecoverableSecurityException) {
                        IntentSender sender = ((RecoverableSecurityException) se)
                                .getUserAction().getActionIntent().getIntentSender();
                        return new EmbedResult(false, "Permission required", sender);
                    }
                }
                return new EmbedResult(false, "Permission denied: " + se.getMessage());
            }

            Log.i(TAG, "Lyrics embedded via scoped storage: " + title);
            return new EmbedResult(true, "Lyrics saved to file");
        } catch (Exception e) {
            Log.e(TAG, "Scoped storage embed failed for " + title, e);
            return new EmbedResult(false, "Embed failed: " + e.getMessage());
        } finally {
            if (tempFile != null) tempFile.delete();
        }
    }

    /**
     * Resolve a content:// URI to an absolute file path via MediaStore.
     * Returns null on Android 10+ where DATA column may be unavailable.
     */
    private static String resolveFilePath(Context context, Uri contentUri) {
        String[] proj = {MediaStore.Audio.Media.DATA};
        try (Cursor c = context.getContentResolver().query(contentUri, proj, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA);
                String path = c.getString(idx);
                if (path != null && new File(path).exists()) return path;
            }
        } catch (Exception e) {
            Log.w(TAG, "resolveFilePath failed, falling back to scoped storage", e);
        }
        return null;
    }
}
