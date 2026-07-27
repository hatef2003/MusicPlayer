package com.example.musicplayer;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.media.RingtoneManager;
import android.provider.Settings;
import android.widget.Toast;

import com.example.musicplayer.model.Song;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

/**
 * Utility methods for song actions: Share, Download, Set as Ringtone,
 * Go to Album, Go to Artist.
 *
 * <p>Extracted so every place that shows the TechContextMenu (TracksFragment,
 * AlbumDetailActivity, PlaylistDetailActivity) can call the same logic.
 */
public final class SongActionsHelper {

    private SongActionsHelper() { }

    // ── Share ──────────────────────────────────────────────────────────

    /**
     * Share a song via the system share sheet.
     * Uses the MediaStore content:// URI so no FileProvider is needed.
     */
    public static void shareSong(Context ctx, Song song) {
        try {
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("audio/*");
            share.putExtra(Intent.EXTRA_STREAM, song.getContentUri());
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            ctx.startActivity(Intent.createChooser(share, "Share \"" + song.getTitle() + "\""));
        } catch (Exception e) {
            Toast.makeText(ctx, "Unable to share: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    // ── Set as Ringtone ────────────────────────────────────────────────

    /**
     * Set the song as the default ringtone.
     * On API 23+ requires WRITE_SETTINGS permission.
     */
    public static void setAsRingtone(Context ctx, Song song) {
        // Check WRITE_SETTINGS permission (API 23+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.System.canWrite(ctx)) {
                Toast.makeText(ctx,
                        "Please grant \"Modify system settings\" permission to set ringtone",
                        Toast.LENGTH_LONG).show();
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS);
                    intent.setData(Uri.parse("package:" + ctx.getPackageName()));
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(intent);
                } catch (Exception e) {
                    Toast.makeText(ctx, "Cannot open settings", Toast.LENGTH_SHORT).show();
                }
                return;
            }
        }

        new Thread(() -> {
            try {
                // Copy to internal ringtones directory
                String ext = getExtension(song.getContentUri(), ctx);
                String fileName = sanitizeFilename(song.getTitle()) + ext;

                ContentResolver cr = ctx.getContentResolver();

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Use MediaStore Ringtones collection
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Audio.Media.DISPLAY_NAME, fileName);
                    values.put(MediaStore.Audio.Media.MIME_TYPE, "audio/*");
                    values.put(MediaStore.Audio.Media.IS_RINGTONE, true);
                    values.put(MediaStore.Audio.Media.RELATIVE_PATH,
                            Environment.DIRECTORY_RINGTONES);

                    Uri collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
                    Uri item = cr.insert(collection, values);
                    if (item == null) {
                        showToast(ctx, "Failed to create ringtone file");
                        return;
                    }

                    InputStream in = cr.openInputStream(song.getContentUri());
                    OutputStream out = cr.openOutputStream(item);
                    if (in == null || out == null) {
                        showToast(ctx, "Failed to copy audio");
                        return;
                    }
                    copyStream(in, out);
                    in.close();
                    out.close();

                    // Set as default ringtone
                    RingtoneManager.setActualDefaultRingtoneUri(ctx,
                            RingtoneManager.TYPE_RINGTONE, item);
                    showToast(ctx, "Set as ringtone ✓");
                } else {
                    // Legacy: copy to Ringtones directory and set
                    File dir = Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_RINGTONES);
                    if (!dir.exists()) dir.mkdirs();
                    File dest = new File(dir, fileName);

                    InputStream in = cr.openInputStream(song.getContentUri());
                    if (in == null) {
                        showToast(ctx, "Cannot read song file");
                        return;
                    }
                    java.io.FileOutputStream fos = new java.io.FileOutputStream(dest);
                    copyStream(in, fos);
                    in.close();
                    fos.close();

                    // Register with MediaStore
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Audio.Media.DATA, dest.getAbsolutePath());
                    values.put(MediaStore.Audio.Media.DISPLAY_NAME, fileName);
                    values.put(MediaStore.Audio.Media.MIME_TYPE, "audio/*");
                    values.put(MediaStore.Audio.Media.IS_RINGTONE, true);
                    Uri uri = cr.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values);
                    if (uri != null) {
                        RingtoneManager.setActualDefaultRingtoneUri(ctx,
                                RingtoneManager.TYPE_RINGTONE, uri);
                    }
                    showToast(ctx, "Set as ringtone ✓");
                }
            } catch (Exception e) {
                showToast(ctx, "Ringtone failed: " + e.getMessage());
            }
        }).start();
    }

    // ── Delete song from device ─────────────────────────────────────

    /**
     * Delete a song from the device via MediaStore.
     * On API 30+ uses MediaStore.createDeleteRequest; on older versions
     * deletes directly (requires WRITE_EXTERNAL_STORAGE on API ≤ 28).
     * The reload callback is invoked after a successful delete so the caller
     * can refresh its list.
     */
    /** Request code used by deleteSong on API 30+. */
    public static final int DELETE_REQUEST_CODE = 4001;
    /** Pending onDeleted callback for API 30+ delete flow. */
    private static Runnable sPendingDeleteCallback;

    /**
     * Call this from the activity's onActivityResult to handle the API 30+
     * delete result. Returns true if the result was consumed.
     */
    public static boolean handleDeleteResult(int requestCode, int resultCode) {
        if (requestCode == DELETE_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK && sPendingDeleteCallback != null) {
                sPendingDeleteCallback.run();
            }
            sPendingDeleteCallback = null;
            return true;
        }
        return false;
    }

    public static void deleteSong(Activity activity, Song song, Runnable onDeleted) {
        new androidx.appcompat.app.AlertDialog.Builder(activity)
                .setTitle("Delete Song")
                .setMessage("Permanently delete \"" + song.getTitle() + "\"?\n\nThis cannot be undone.")
                .setPositiveButton("Delete", (dialog, which) -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        // API 30+: use system delete request (shows a confirmation dialog)
                        try {
                            sPendingDeleteCallback = onDeleted;
                            java.util.List<Uri> uris = new java.util.ArrayList<>();
                            uris.add(song.getContentUri());
                            android.app.PendingIntent pi = MediaStore.createDeleteRequest(
                                    activity.getContentResolver(), uris);
                            activity.startIntentSenderForResult(
                                    pi.getIntentSender(), DELETE_REQUEST_CODE, null, 0, 0, 0);
                        } catch (Exception e) {
                            sPendingDeleteCallback = null;
                            Toast.makeText(activity, "Delete failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                        }
                    } else {
                        // Legacy: delete directly
                        new Thread(() -> {
                            try {
                                int deleted = activity.getContentResolver().delete(
                                        song.getContentUri(), null, null);
                                activity.runOnUiThread(() -> {
                                    if (deleted > 0) {
                                        Toast.makeText(activity, "Deleted", Toast.LENGTH_SHORT).show();
                                        if (onDeleted != null) onDeleted.run();
                                    } else {
                                        Toast.makeText(activity, "Could not delete", Toast.LENGTH_SHORT).show();
                                    }
                                });
                            } catch (Exception e) {
                                activity.runOnUiThread(() ->
                                        Toast.makeText(activity, "Delete failed: " + e.getMessage(),
                                                Toast.LENGTH_SHORT).show());
                            }
                        }).start();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ── Go to Album ────────────────────────────────────────────────────

    /**
     * Navigate to AlbumDetailActivity for the song's album.
     */
    public static void goToAlbum(Activity activity, Song song) {
        try {
            Intent intent = new Intent(activity, AlbumDetailActivity.class);
            intent.putExtra(AlbumDetailActivity.EXTRA_ALBUM_ID, song.getAlbumId());
            intent.putExtra(AlbumDetailActivity.EXTRA_ALBUM_NAME, song.getAlbum());
            intent.putExtra(AlbumDetailActivity.EXTRA_ALBUM_ARTIST, song.getArtist());
            activity.startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(activity, "Cannot open album", Toast.LENGTH_SHORT).show();
        }
    }

    // ── Go to Artist ───────────────────────────────────────────────────

    /**
     * Navigate to MainActivity and filter the tracks list by the song's artist.
     * Passes the artist name as an intent extra so MainActivity can apply the filter.
     */
    public static void goToArtist(Activity activity, Song song) {
        try {
            Intent intent = new Intent(activity, MainActivity.class);
            intent.putExtra("filter_artist", song.getArtist());
            intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            activity.startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(activity, "Cannot navigate to artist", Toast.LENGTH_SHORT).show();
        }
    }

    // ── Internal helpers ───────────────────────────────────────────────

    private static void copyStream(InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[8192];
        int len;
        while ((len = in.read(buf)) > 0) {
            out.write(buf, 0, len);
        }
    }

    private static String getExtension(Uri uri, Context ctx) {
        // Try from content type
        String mime = ctx.getContentResolver().getType(uri);
        if (mime != null) {
            if (mime.contains("mpeg") || mime.contains("mp3")) return ".mp3";
            if (mime.contains("flac")) return ".flac";
            if (mime.contains("aac")) return ".aac";
            if (mime.contains("ogg")) return ".ogg";
            if (mime.contains("wav")) return ".wav";
            if (mime.contains("x-m4a") || mime.contains("mp4")) return ".m4a";
        }
        // Try from URI path
        String path = uri.getPath();
        if (path != null) {
            int dot = path.lastIndexOf('.');
            if (dot >= 0) return path.substring(dot);
        }
        return ".mp3"; // fallback
    }

    private static String sanitizeFilename(String name) {
        if (name == null || name.isEmpty()) return "track";
        return name.replaceAll("[^a-zA-Z0-9_\\-\\s]", "_").trim();
    }

    private static void showToast(Context ctx, String msg) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(
                () -> Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show());
    }
}
