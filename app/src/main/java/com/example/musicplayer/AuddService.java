package com.example.musicplayer;

import android.app.RecoverableSecurityException;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.IntentSender;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.util.Log;

import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.UUID;

/**
 * Identifies songs using the AudD music recognition API.
 * Sends audio data and returns title, artist, album.
 */
public class AuddService {

    private static final String TAG = "AuddService";
    private static final String AUDD_ENDPOINT = "https://api.audd.io/";

    /** Maximum bytes to send (~500 KB, covers ~15 seconds of audio at most bitrates). */
    private static final int MAX_AUDIO_BYTES = 512 * 1024;

    /** Holds both the temp file and its detected MIME type. */
    private static class AudioSnippet {
        final File file;
        final String mimeType;

        AudioSnippet(File file, String mimeType) {
            this.file = file;
            this.mimeType = mimeType;
        }
    }

    /** Identification result returned by AudD. */
    public static class Result {
        public final String title;
        public final String artist;
        public final String album;
        public final String releaseDate;
        public final String rawResponse;

        public Result(String title, String artist, String album, String releaseDate, String rawResponse) {
            this.title = title;
            this.artist = artist;
            this.album = album;
            this.releaseDate = releaseDate;
            this.rawResponse = rawResponse;
        }
    }

    /**
     * Identify a song from its content URI.
     * Extracts a portion of the audio and sends it to AudD.
     *
     * @param context   application context
     * @param audioUri  content:// URI of the audio file
     * @param apiKey    AudD API token
     * @return identification result, or null if not identified
     * @throws Exception on network or IO errors
     */
    public static Result identify(Context context, Uri audioUri, String apiKey) throws Exception {
        AudioSnippet snippet = extractAudioSnippet(context, audioUri);
        try {
            return sendToAudd(snippet.file, apiKey, snippet.mimeType);
        } finally {
            if (snippet.file != null && snippet.file.exists()) {
                snippet.file.delete();
            }
        }
    }

    /**
     * Extract a ~15-second audio snippet from ~30% into the song.
     * Skipping the intro avoids silence, fade-ins, and spoken intros that
     * confuse audio fingerprinting (AudD recommends the chorus/main section).
     *
     * Strategy:
     * 1. Get total duration via MediaMetadataRetriever
     * 2. Calculate skip offset at ~30% of the file size (or 30 seconds in)
     * 3. Skip forward by discarding bytes, then capture MAX_AUDIO_BYTES
     * If duration is unknown or file is very short, fall back to reading from start.
     */
    private static AudioSnippet extractAudioSnippet(Context context, Uri audioUri) throws Exception {
        File tempDir = new File(context.getCacheDir(), "audd");
        if (!tempDir.exists()) tempDir.mkdirs();

        // Detect MIME type and duration
        String extension = ".mp3";
        String detectedMime = "audio/mpeg";
        long durationMs = 0;
        try {
            MediaMetadataRetriever mmr = new MediaMetadataRetriever();
            mmr.setDataSource(context, audioUri);
            String mime = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE);
            if (mime != null) {
                detectedMime = mime;
                if (mime.contains("flac")) extension = ".flac";
                else if (mime.contains("ogg")) extension = ".ogg";
                else if (mime.contains("aac") || mime.contains("mp4")) extension = ".m4a";
                else if (mime.contains("wav")) extension = ".wav";
            }
            String dur = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (dur != null) durationMs = Long.parseLong(dur);
            mmr.release();
        } catch (Exception ignored) { }

        File tempFile = new File(tempDir, "snippet_" + UUID.randomUUID() + extension);

        ContentResolver resolver = context.getContentResolver();
        InputStream in = resolver.openInputStream(audioUri);
        if (in == null) throw new Exception("Cannot open audio file");

        try {
            // Calculate how many bytes to skip to reach ~30% into the song.
            // For a typical MP3 at 128kbps: 1 second ≈ 16KB.
            // We skip min(30% of total, 30 seconds worth) but at least skip nothing
            // for very short files.
            long skipBytes = 0;
            if (durationMs > 15000) {
                // Skip to ~30% of the song (but between 10s and 60s in)
                long skipSeconds = Math.max(10, Math.min(60, durationMs / 1000 / 3));
                // Rough estimate: ~16KB per second for 128kbps MP3
                skipBytes = skipSeconds * 16 * 1024;
                Log.d(TAG, "Skipping ~" + skipSeconds + "s (" + skipBytes + " bytes) into song");
            }

            // Skip forward using efficient InputStream.skip()
            if (skipBytes > 0) {
                long skipped = 0;
                while (skipped < skipBytes) {
                    long n = in.skip(skipBytes - skipped);
                    if (n <= 0) break;
                    skipped += n;
                }
                Log.d(TAG, "Actually skipped " + skipped + " bytes");
            }

            // Capture ~15 seconds of audio
            FileOutputStream out = new FileOutputStream(tempFile);
            byte[] buffer = new byte[8192];
            int totalRead = 0;
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                int remaining = MAX_AUDIO_BYTES - totalRead;
                int toWrite = Math.min(bytesRead, remaining);
                out.write(buffer, 0, toWrite);
                totalRead += toWrite;
                if (totalRead >= MAX_AUDIO_BYTES) break;
            }
            out.flush();
            out.close();
            Log.d(TAG, "Captured " + totalRead + " bytes of audio snippet");

            // Fallback: if we captured nothing (skipped past EOF), re-read from start
            if (totalRead == 0) {
                Log.w(TAG, "Snippet empty after skip — re-reading from start");
                in.close();
                in = resolver.openInputStream(audioUri);
                if (in != null) {
                    out = new FileOutputStream(tempFile);
                    totalRead = 0;
                    while ((bytesRead = in.read(buffer)) != -1) {
                        int remaining = MAX_AUDIO_BYTES - totalRead;
                        int toWrite = Math.min(bytesRead, remaining);
                        out.write(buffer, 0, toWrite);
                        totalRead += toWrite;
                        if (totalRead >= MAX_AUDIO_BYTES) break;
                    }
                    out.flush();
                    out.close();
                    Log.d(TAG, "Fallback captured " + totalRead + " bytes");
                }
            }
        } finally {
            if (in != null) in.close();
        }

        return new AudioSnippet(tempFile, detectedMime);
    }

    /**
     * Send the audio file to AudD API and parse the response.
     */
    private static Result sendToAudd(File audioFile, String apiKey, String mimeType) throws Exception {
        String boundary = "----AuddBoundary" + System.currentTimeMillis();
        String LINE_FEED = "\r\n";

        URL url = new URL(AUDD_ENDPOINT);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setDoOutput(true);
        conn.setDoInput(true);
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

        OutputStream out = conn.getOutputStream();

        // api_token field
        out.write(("--" + boundary + LINE_FEED).getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"api_token\"" + LINE_FEED).getBytes(StandardCharsets.UTF_8));
        out.write(LINE_FEED.getBytes(StandardCharsets.UTF_8));
        out.write(apiKey.getBytes(StandardCharsets.UTF_8));
        out.write(LINE_FEED.getBytes(StandardCharsets.UTF_8));

        // return field (request Spotify/Apple Music metadata)
        out.write(("--" + boundary + LINE_FEED).getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"return\"" + LINE_FEED).getBytes(StandardCharsets.UTF_8));
        out.write(LINE_FEED.getBytes(StandardCharsets.UTF_8));
        out.write("spotify,apple_music".getBytes(StandardCharsets.UTF_8));
        out.write(LINE_FEED.getBytes(StandardCharsets.UTF_8));

        // file field
        out.write(("--" + boundary + LINE_FEED).getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"file\"; filename=\"" + audioFile.getName() + "\"" + LINE_FEED).getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Type: " + mimeType + LINE_FEED).getBytes(StandardCharsets.UTF_8));
        out.write(LINE_FEED.getBytes(StandardCharsets.UTF_8));

        // Write audio data
        java.io.FileInputStream fis = new java.io.FileInputStream(audioFile);
        byte[] buffer = new byte[8192];
        int bytesRead;
        while ((bytesRead = fis.read(buffer)) != -1) {
            out.write(buffer, 0, bytesRead);
        }
        fis.close();
        out.write(LINE_FEED.getBytes(StandardCharsets.UTF_8));

        // End boundary
        out.write(("--" + boundary + "--" + LINE_FEED).getBytes(StandardCharsets.UTF_8));
        out.flush();
        out.close();

        // Read response
        int responseCode = conn.getResponseCode();
        InputStream responseStream = (responseCode >= 200 && responseCode < 300)
                ? conn.getInputStream() : conn.getErrorStream();

        BufferedReader reader = new BufferedReader(new InputStreamReader(responseStream, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }
        reader.close();
        conn.disconnect();

        String responseBody = sb.toString();
        Log.d(TAG, "AudD response: " + responseBody);

        return parseResponse(responseBody);
    }

    /**
     * Parse the AudD JSON response into a Result.
     */
    private static Result parseResponse(String json) throws Exception {
        JSONObject root = new JSONObject(json);
        String status = root.optString("status", "");

        if (!"success".equals(status)) {
            String error = root.optString("error", "Unknown error");
            throw new Exception("AudD error: " + error);
        }

        JSONObject result = root.optJSONObject("result");
        if (result == null) {
            // No match found
            return null;
        }

        String title = result.optString("title", null);
        String artist = result.optString("artist", null);
        String album = result.optString("album", null);
        String releaseDate = result.optString("release_date", null);

        if (title == null && artist == null) {
            return null;
        }

        return new Result(title, artist, album, releaseDate, json);
    }

    /**
     * Update a song's metadata in both MediaStore AND the audio file's ID3/Vorbis tags.
     * Returns true if at least one field was updated in MediaStore.
     *
     * @param contentUri  the content:// URI of the audio file (for writing tags)
     * @param intentSenderOut  if non-null and write permission is needed, this is set so the
     *                         caller can request user consent
     */
    public static boolean updateSongMetadata(Context context, long songId, Uri contentUri,
                                              String newTitle, String newArtist, String newAlbum,
                                              IntentSender[] intentSenderOut) {
        return updateSongMetadata(context, songId, contentUri, newTitle, newArtist, newAlbum, intentSenderOut, null);
    }

    /**
     * Overload with a completion callback that fires after MediaScanner finishes re-indexing.
     * The callback runs on a background thread — callers must use runOnUiThread() for UI updates.
     */
    public static boolean updateSongMetadata(Context context, long songId, Uri contentUri,
                                              String newTitle, String newArtist, String newAlbum,
                                              IntentSender[] intentSenderOut,
                                              Runnable onScanComplete) {
        if (contentUri == null) return false;

        // On Android 10+, TITLE/ARTIST/ALBUM are read-only in MediaStore.
        // We must write the file tags first, then trigger a MediaScanner re-scan
        // so MediaStore picks up the new metadata from the file.

        // Step 1: Write tags to the actual audio file
        EmbedResult tagResult = writeTagsToFile(context, contentUri, newTitle, newArtist, newAlbum);

        if (tagResult.intentSender != null && intentSenderOut != null) {
            intentSenderOut[0] = tagResult.intentSender;
            return false; // Need user permission first
        }

        if (!tagResult.success) {
            Log.w(TAG, "Could not write tags to file: " + tagResult.message);
            // On older Android, try direct MediaStore update as fallback
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                boolean ok = updateMediaStoreDirect(context, songId, newTitle, newArtist, newAlbum) > 0;
                if (ok && onScanComplete != null) onScanComplete.run();
                return ok;
            }
            return false;
        }

        Log.i(TAG, "Tags written to audio file: " + newTitle);

        // Step 2: Trigger MediaScanner to re-index the file so MediaStore picks up new metadata
        triggerMediaScan(context, contentUri, onScanComplete);

        // Step 3: Also try direct MediaStore update (works on some devices/versions)
        updateMediaStoreDirect(context, songId, newTitle, newArtist, newAlbum);

        return true;
    }

    /**
     * Directly update MediaStore columns. This works pre-API 29 and sometimes on API 29+
     * depending on the device. Silently returns 0 on devices where columns are read-only.
     */
    private static int updateMediaStoreDirect(Context context, long songId,
                                               String newTitle, String newArtist, String newAlbum) {
        try {
            Uri itemUri = ContentUris.withAppendedId(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, songId);
            ContentValues values = new ContentValues();
            if (newTitle != null && !newTitle.isEmpty()) {
                values.put(MediaStore.Audio.Media.TITLE, newTitle);
            }
            if (newArtist != null && !newArtist.isEmpty()) {
                values.put(MediaStore.Audio.Media.ARTIST, newArtist);
            }
            if (newAlbum != null && !newAlbum.isEmpty()) {
                values.put(MediaStore.Audio.Media.ALBUM, newAlbum);
            }
            if (values.size() > 0) {
                int updated = context.getContentResolver().update(itemUri, values, null, null);
                Log.d(TAG, "Direct MediaStore update: " + updated + " row(s) for song ID " + songId);
                return updated;
            }
        } catch (Exception e) {
            Log.d(TAG, "Direct MediaStore update failed (expected on API 29+): " + e.getMessage());
        }
        return 0;
    }

    /**
     * Trigger the system MediaScanner to re-read the file's metadata tags.
     * This makes MediaStore pick up the new title/artist/album we wrote to the file.
     * @param onComplete optional callback fired after the scan finishes (on a binder thread)
     */
    private static void triggerMediaScan(Context context, Uri contentUri, Runnable onComplete) {
        try {
            String filePath = resolveFilePath(context, contentUri);
            if (filePath != null) {
                android.media.MediaScannerConnection.scanFile(
                        context, new String[]{filePath}, null,
                        (path, uri) -> {
                            Log.d(TAG, "MediaScanner scan completed for: " + path);
                            if (onComplete != null) onComplete.run();
                        });
                Log.d(TAG, "MediaScanner scan triggered for: " + filePath);
            } else {
                Log.w(TAG, "Cannot trigger scan: file path not resolved");
                // Still fire callback so caller isn't stuck waiting forever
                if (onComplete != null) onComplete.run();
            }
        } catch (Exception e) {
            Log.d(TAG, "MediaScanner scan failed: " + e.getMessage());
            if (onComplete != null) onComplete.run();
        }
    }

    /** Result of a tag-writing attempt (reuses the same pattern as LyricsEmbedder). */
    public static class EmbedResult {
        public final boolean success;
        public final String message;
        public final IntentSender intentSender;

        EmbedResult(boolean s, String m) { success = s; message = m; intentSender = null; }
        EmbedResult(boolean s, String m, IntentSender is) { success = s; message = m; intentSender = is; }
    }

    /**
     * Write title/artist/album tags into the audio file using jaudiotagger.
     * Tries direct file path first, falls back to scoped storage (copy → modify → write back).
     */
    public static EmbedResult writeTagsToFile(Context context, Uri contentUri,
                                               String title, String artist, String album) {
        // 1. Try direct file path (works on API < 29 and some devices)
        String filePath = resolveFilePath(context, contentUri);
        if (filePath != null) {
            EmbedResult directResult = writeTagsDirect(new File(filePath), title, artist, album);
            if (directResult.success) return directResult;
            // Fall through to scoped storage if direct write failed
        }

        // 2. Scoped storage: copy → modify → write back
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return writeTagsViaScopedStorage(context, contentUri, title, artist, album);
        }

        return new EmbedResult(false, "Could not access file");
    }

    /** Write tags when we have direct file path access. */
    private static EmbedResult writeTagsDirect(File audioFile, String title, String artist, String album) {
        try {
            if (!audioFile.exists()) return new EmbedResult(false, "File not found");
            if (!audioFile.canWrite()) return new EmbedResult(false, "File not writable");

            AudioFile af = AudioFileIO.read(audioFile);
            Tag tag = af.getTagOrCreateDefault();
            if (title != null && !title.isEmpty()) tag.setField(FieldKey.TITLE, title);
            if (artist != null && !artist.isEmpty()) tag.setField(FieldKey.ARTIST, artist);
            if (album != null && !album.isEmpty()) tag.setField(FieldKey.ALBUM, album);
            AudioFileIO.write(af);

            Log.i(TAG, "Tags written via direct file: " + title);
            return new EmbedResult(true, "Tags saved to file");
        } catch (Exception e) {
            Log.e(TAG, "Failed to write tags for " + title, e);
            return new EmbedResult(false, "Tag write failed: " + e.getMessage());
        }
    }

    /**
     * Write tags via scoped storage: copy to temp, modify with jaudiotagger, write back.
     */
    private static EmbedResult writeTagsViaScopedStorage(Context context, Uri uri,
                                                          String title, String artist, String album) {
        File tempFile = null;
        try {
            // Copy to temp file with correct extension
            String ext = getFileExtension(context, uri);
            tempFile = File.createTempFile("tag_write_", ext, context.getCacheDir());
            try (InputStream in = context.getContentResolver().openInputStream(uri)) {
                if (in == null) return new EmbedResult(false, "Cannot read file");
                try (FileOutputStream out = new FileOutputStream(tempFile)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
            }

            // Modify temp file with jaudiotagger
            AudioFile af = AudioFileIO.read(tempFile);
            Tag tag = af.getTagOrCreateDefault();
            if (title != null && !title.isEmpty()) tag.setField(FieldKey.TITLE, title);
            if (artist != null && !artist.isEmpty()) tag.setField(FieldKey.ARTIST, artist);
            if (album != null && !album.isEmpty()) tag.setField(FieldKey.ALBUM, album);
            AudioFileIO.write(af);

            // Write modified file back via content resolver
            try (OutputStream out = context.getContentResolver().openOutputStream(uri, "wt")) {
                if (out == null) return new EmbedResult(false, "Cannot write to file");
                try (InputStream in = new java.io.FileInputStream(tempFile)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
            }

            Log.i(TAG, "Tags written via scoped storage: " + title);
            return new EmbedResult(true, "Tags saved to file");
        } catch (SecurityException se) {
            Log.w(TAG, "Write permission denied, requesting user consent: " + se.getMessage());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                IntentSender sender = MediaStore.createWriteRequest(
                        context.getContentResolver(), Collections.singletonList(uri)).getIntentSender();
                return new EmbedResult(false, "Permission required", sender);
            } else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
                if (se instanceof RecoverableSecurityException) {
                    IntentSender sender = ((RecoverableSecurityException) se)
                            .getUserAction().getActionIntent().getIntentSender();
                    return new EmbedResult(false, "Permission required", sender);
                }
            }
            return new EmbedResult(false, "Permission denied: " + se.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "Scoped storage tag write failed for " + title, e);
            return new EmbedResult(false, "Tag write failed: " + e.getMessage());
        } finally {
            if (tempFile != null) tempFile.delete();
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
        } catch (Exception ignored) { }
        return ".mp3";
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
