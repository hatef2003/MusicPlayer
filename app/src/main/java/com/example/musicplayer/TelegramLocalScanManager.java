package com.example.musicplayer;

import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.FileFilter;
import java.util.ArrayList;
import java.util.List;

/**
 * Scans Telegram's local cache directories for downloaded music files.
 *
 * Telegram (official app, package org.telegram.messenger) stores downloaded
 * media in:
 *     *   API 29+ (Android 10+):
     *     Android/data/org.telegram.messenger/files/     (most common)
     *     Android/media/org.telegram.messenger/         (newer Android versions)
     *     storage/emulated/0/Telegram/                  (legacy)
     *
     *   The 'files/' subfolder under org.telegram.messenger contains type-arranged
     *   subdirectories (Audio, Video, Images, Documents, Download, cache, etc.).
     *   Some Telegram installs use custom storage paths set by the user.
 *
 *   Legacy (API < 29 or some manufacturers):
 *     /storage/emulated/0/Telegram/
 *         Audio/
 *         Voice Notes/
 *         Documents/
 *         (date-based subfolders)/
 *
 * Access to Android/data/ is restricted by scoped storage on Android 11+
 * (API 30).  We attempt to read it directly — this works on many devices
 * but is blocked on some OEMs and strictly-enforced Android 11+ installs.
 *
 * Fallback: on all platforms we also query MediaStore for any Telegram
 * files that were already indexed by the system media scanner.
 */
public class TelegramLocalScanManager {

    private static final String TAG = "TelegramLocalScan";
    private static final String TELEGRAM_PACKAGE = "org.telegram.messenger";

    // Audio extensions that Telegram commonly uses for music files
    private static final String[] AUDIO_EXTENSIONS = {
            ".mp3", ".flac", ".wav", ".ogg", ".m4a", ".aac",
            ".wma", ".opus", ".aiff", ".alac", ".dsf", ".dff"
    };

    private final Context appContext;

    public TelegramLocalScanManager(Context context) {
        this.appContext = context.getApplicationContext();
    }

    /**
     * Scan all known Telegram cache locations and return a list of audio files
     * found there.  Each entry is a simple bean with path, name, size, and duration
     * (duration from MediaStore where available).
     */
    public List<TelegramLocalFile> scan() {
        List<TelegramLocalFile> result = new ArrayList<>();

        // 1. Android/data/org.telegram.messenger/files/ (most common location)
        File telegramFilesDir = getTelegramFilesDir();
        if (telegramFilesDir != null && telegramFilesDir.exists()) {
            Log.d(TAG, "Scanning: " + telegramFilesDir.getAbsolutePath());
            scanDirectoryRecursive(result, telegramFilesDir, 0);
        }

        // 1b. Android/data/org.telegram.messenger/audio/ (some Telegram installs use this path)
        File telegramAudioDir = getTelegramAudioDir();
        if (telegramAudioDir != null && telegramAudioDir.exists()) {
            Log.d(TAG, "Scanning audio dir: " + telegramAudioDir.getAbsolutePath());
            scanDirectoryRecursive(result, telegramAudioDir, 0);
        }

        // 1c. Android/data/org.telegram.messenger/cache/ (Telegram's cache folder — may have audio)
        File telegramCacheDir = getTelegramCacheDir();
        if (telegramCacheDir != null && telegramCacheDir.exists()) {
            Log.d(TAG, "Scanning cache dir: " + telegramCacheDir.getAbsolutePath());
            scanDirectoryRecursive(result, telegramCacheDir, 0);
        }

        // 2. Android/media/org.telegram.messenger/ (newer Android 11+ location)
        File telegramMediaDir = getTelegramMediaDir();
        if (telegramMediaDir != null) {
            Log.d(TAG, "Scanning media dir: " + telegramMediaDir.getAbsolutePath()
                    + (telegramMediaDir.exists() ? " (exists)" : " (NOT accessible)"));
            if (telegramMediaDir.exists()) {
                scanDirectoryRecursive(result, telegramMediaDir, 0);
            }
        }

        // 3. Also try Android/media/org.telegram.messenger/files/ explicitly
        File extStorage = Environment.getExternalStorageDirectory();
        if (extStorage != null) {
            File explicitMediaFiles = new File(extStorage, "Android/media/" + TELEGRAM_PACKAGE + "/files");
            if (explicitMediaFiles.exists()) {
                Log.d(TAG, "Scanning explicit media/files: " + explicitMediaFiles.getAbsolutePath());
                scanDirectoryRecursive(result, explicitMediaFiles, 0);
            }
        }

        // 3. Legacy /Telegram/ root folder (older Telegram installs)
        File legacyTelegramDir = new File(Environment.getExternalStorageDirectory(), "Telegram");
        if (legacyTelegramDir.exists()) {
            Log.d(TAG, "Scanning: " + legacyTelegramDir.getAbsolutePath());
            scanDirectoryRecursive(result, legacyTelegramDir, 0);
        }

        // 4. Also check the standard Downloads folder for Telegram-saved files
        File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (downloadsDir != null && downloadsDir.exists()) {
            File telegramDownloads = new File(downloadsDir, "Telegram");
            if (telegramDownloads.exists()) {
                Log.d(TAG, "Scanning Downloads/Telegram: " + telegramDownloads.getAbsolutePath());
                scanDirectoryRecursive(result, telegramDownloads, 0);
            }
        }

        // 5. Also check Telegram subfolder in Pictures/DCIM (for saved media)
        File dcimDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
        if (dcimDir != null && dcimDir.exists()) {
            File telegramDcim = new File(dcimDir, "Telegram");
            if (telegramDcim.exists()) {
                Log.d(TAG, "Scanning DCIM/Telegram: " + telegramDcim.getAbsolutePath());
                scanDirectoryRecursive(result, telegramDcim, 0);
            }
        }

        // 3. Also check MediaStore for anything already indexed from Telegram's folders
        addFromMediaStore(result);

        // 3b. Broad MediaStore scan: get ALL audio files and filter by path containing
        //      known Telegram directories.  This catches files that direct-scan can't
        //      reach due to scoped-storage restrictions.
        addFromBroadMediaStore(result);

        // Deduplicate by path
        result = deduplicateByPath(result);

        // Sort by date added descending (newest first)
        result.sort((a, b) -> Long.compare(b.dateAdded, a.dateAdded));

        Log.i(TAG, "Telegram local scan complete: " + result.size() + " files found");
        return result;
    }

    /**
     * Recursively scan a directory tree for audio files.
     * @param maxDepth limits recursion depth to avoid scanning huge trees
     */
    private void scanDirectoryRecursive(List<TelegramLocalFile> result, File dir, int depth) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) return;
        if (depth > 10) return; // safety limit
        File[] files = dir.listFiles();
        if (files == null) {
            Log.w(TAG, "Cannot list directory (permission/OEM restriction?): " + dir.getAbsolutePath());
            return;
        }
        for (File f : files) {
            if (f.isDirectory()) {
                scanDirectoryRecursive(result, f, depth + 1);
            } else if (f.isFile() && f.canRead()) {
                String name = f.getName().toLowerCase();
                boolean isAudio = false;
                for (String ext : AUDIO_EXTENSIONS) {
                    if (name.endsWith(ext)) {
                        isAudio = true;
                        break;
                    }
                }
                if (!isAudio) continue;

                long durationMs = 0;
                long dateAdded = f.lastModified();
                try {
                    MediaStoreAudioInfo info = queryMediaStoreByPath(f.getAbsolutePath());
                    if (info != null) {
                        durationMs = info.duration;
                        if (info.dateAdded > 0) dateAdded = info.dateAdded;
                    }
                } catch (Exception e) {
                    Log.w(TAG, "MediaStore query failed for " + f.getName(), e);
                }

                result.add(new TelegramLocalFile(
                        f.getAbsolutePath(),
                        f.getName(),
                        f.length(),
                        durationMs,
                        dateAdded
                ));
            }
        }
    }

    private void addFromMediaStore(List<TelegramLocalFile> result) {
        // On API 29+ we can query MediaStore for files in Telegram's folders
        if (Build.VERSION.SDK_INT < 29) return;

        String[] projection = new String[]{
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DATE_ADDED,
                MediaStore.Audio.Media.DISPLAY_NAME
        };

        // Match files in Telegram-related paths (scoped-storage, media, legacy, downloads)
        String selection = MediaStore.Audio.Media.RELATIVE_PATH + " LIKE ? OR " +
                MediaStore.Audio.Media.DATA + " LIKE ?";
        String telegramPathPattern = "%" + TELEGRAM_PACKAGE + "%";
        String legacyTelegramPattern = "%/Telegram/%";
        String[] selectionArgs = new String[]{telegramPathPattern, legacyTelegramPattern,
                "%/Download/Telegram/%", "%/DCIM/Telegram/%",
                telegramPathPattern, legacyTelegramPattern};

        try (android.database.Cursor cursor = appContext.getContentResolver().query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection, selection, selectionArgs,
                MediaStore.Audio.Media.DATE_ADDED + " DESC")) {

            if (cursor == null) return;

            int pathCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA);
            int displayNameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME);
            int durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
            int dateAddedCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED);

            // Build a set of paths we already have from direct scan to avoid duplicates
            java.util.Set<String> seenPaths = new java.util.HashSet<>();
            for (TelegramLocalFile f : result) seenPaths.add(f.path);

            while (cursor.moveToNext()) {
                String data = cursor.getString(pathCol);
                if (data == null || data.isEmpty()) continue;
                if (seenPaths.contains(data)) continue;

                File f = new File(data);
                if (!f.exists() || !f.canRead()) continue;

                String name = cursor.getString(displayNameCol);
                if (name == null) name = f.getName();

                long duration = cursor.getLong(durationCol);
                long dateAdded = cursor.getLong(dateAddedCol);
                if (dateAdded == 0) dateAdded = f.lastModified();

                result.add(new TelegramLocalFile(
                        data, name, f.length(), duration, dateAdded
                ));
                seenPaths.add(data);
            }
        } catch (Exception e) {
            Log.w(TAG, "MediaStore Telegram scan failed", e);
        }
    }

    /**
     * Try to look up a file in MediaStore by its absolute path (API 29+).
     * Returns null if not found or on older platforms.
     */
    private MediaStoreAudioInfo queryMediaStoreByPath(String filePath) {
        if (Build.VERSION.SDK_INT < 29) return null;

        String[] projection = new String[]{
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DATE_ADDED
        };
        String selection = MediaStore.Audio.Media.DATA + "=?";
        String[] selectionArgs = new String[]{filePath};

        try (android.database.Cursor cursor = appContext.getContentResolver().query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection, selection, selectionArgs, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                long duration = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION));
                long dateAdded = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED));
                return new MediaStoreAudioInfo(duration, dateAdded);
            }
        } catch (Exception e) {
            Log.w(TAG, "MediaStore path lookup failed for " + filePath, e);
        }
        return null;
    }

    /** Minimal info from MediaStore for a single file lookup. */
    private static class MediaStoreAudioInfo {
        final long duration;
        final long dateAdded;
        MediaStoreAudioInfo(long duration, long dateAdded) {
            this.duration = duration;
            this.dateAdded = dateAdded;
        }
    }

    private File getTelegramMediaDir() {
        // Android 11+ may store Telegram's shared files under Android/media/org.telegram.messenger/
        File extStorage = Environment.getExternalStorageDirectory();
        if (extStorage == null) return null;
        File androidMedia = new File(extStorage, "Android/media");
        File telegramMedia = new File(androidMedia, TELEGRAM_PACKAGE);
        // Return the 'files' subfolder if it exists, otherwise the package root
        File filesDir = new File(telegramMedia, "files");
        if (filesDir.exists()) return filesDir;
        return telegramMedia.exists() ? telegramMedia : null;
    }

    private File getTelegramAudioDir() {
        File extStorage = Environment.getExternalStorageDirectory();
        if (extStorage == null) return null;
        File androidData = new File(extStorage, "Android/data");
        if (!androidData.exists()) return null;
        File telegramData = new File(androidData, TELEGRAM_PACKAGE);
        if (!telegramData.exists()) return null;
        File audioDir = new File(telegramData, "audio");
        return audioDir.exists() ? audioDir : null;
    }

    private File getTelegramCacheDir() {
        File extStorage = Environment.getExternalStorageDirectory();
        if (extStorage == null) return null;
        File androidData = new File(extStorage, "Android/data");
        if (!androidData.exists()) return null;
        File telegramData = new File(androidData, TELEGRAM_PACKAGE);
        if (!telegramData.exists()) return null;
        File cacheDir = new File(telegramData, "cache");
        return cacheDir.exists() ? cacheDir : null;
    }

    private File getTelegramFilesDir() {
        // Try to locate Android/data/org.telegram.messenger/files/
        // Use the external storage directory as the base
        File extStorage = Environment.getExternalStorageDirectory();
        if (extStorage == null) return null;

        // Build path: <extStorage>/Android/data/org.telegram.messenger/files/
        File androidData = new File(extStorage, "Android/data");
        if (!androidData.exists()) return null;

        File telegramData = new File(androidData, TELEGRAM_PACKAGE);
        if (!telegramData.exists()) return null;

        File filesDir = new File(telegramData, "files");
        if (!filesDir.exists()) return null;

        return filesDir;
    }

    /**
     * Broad MediaStore scan: query ALL audio in the system and keep only those
     * stored in Telegram-related directories.  This is the most reliable way to
     * find Telegram files on Android 11+ where direct file access is restricted.
     */
    private void addFromBroadMediaStore(List<TelegramLocalFile> result) {
        if (Build.VERSION.SDK_INT < 29) return;

        String[] projection = new String[]{
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DATE_ADDED,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.RELATIVE_PATH
        };

        java.util.Set<String> seenPaths = new java.util.HashSet<>();
        for (TelegramLocalFile f : result) seenPaths.add(f.path);

        int count = 0;
        try (android.database.Cursor cursor = appContext.getContentResolver().query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection, null, null,
                MediaStore.Audio.Media.DATE_ADDED + " DESC")) {

            if (cursor == null) return;

            int pathCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA);
            int relPathCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH);
            int displayNameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME);
            int durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
            int dateAddedCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED);

            while (cursor.moveToNext()) {
                count++;
                String data = cursor.getString(pathCol);
                String relPath = cursor.getString(relPathCol);
                if (data == null && relPath == null) continue;

                boolean isTelegram = false;
                String pathToCheck = (data != null) ? data : "";
                if (relPath != null) pathToCheck = relPath;

                if (pathToCheck.contains(TELEGRAM_PACKAGE)
                        || pathToCheck.contains("/Telegram/")
                        || (data != null && new File(data).getName().toLowerCase().contains("telegram"))) {
                    isTelegram = true;
                }

                if (!isTelegram) continue;
                if (data != null && seenPaths.contains(data)) continue;

                File f = (data != null) ? new File(data) : null;
                if (f != null && (!f.exists() || !f.canRead())) continue;

                String name = cursor.getString(displayNameCol);
                if (name == null && f != null) name = f.getName();
                if (name == null) continue;

                long duration = cursor.getLong(durationCol);
                long dateAdded = cursor.getLong(dateAddedCol);
                if (dateAdded == 0 && f != null) dateAdded = f.lastModified();
                long fileSize = (f != null) ? f.length() : 0;

                result.add(new TelegramLocalFile(
                        data != null ? data : "",
                        name, fileSize, duration, dateAdded
                ));
                if (data != null) seenPaths.add(data);
            }
        } catch (Exception e) {
            Log.w(TAG, "Broad MediaStore scan failed", e);
        }
        Log.d(TAG, "Broad MediaStore: scanned " + count + " total audio files");
    }

    /** Remove duplicate entries by absolute path, keeping the one with more info. */
    private List<TelegramLocalFile> deduplicateByPath(List<TelegramLocalFile> list) {
        java.util.Map<String, TelegramLocalFile> best = new java.util.LinkedHashMap<>();
        for (TelegramLocalFile f : list) {
            TelegramLocalFile existing = best.get(f.path);
            if (existing == null) {
                best.put(f.path, f);
            } else {
                // Prefer the entry with a known duration
                if (f.durationMs > 0 && existing.durationMs == 0) {
                    best.put(f.path, f);
                }
            }
        }
        return new ArrayList<>(best.values());
    }

    /**
     * Simple DTO for a music file found in Telegram's cache.
     */
    public static class TelegramLocalFile {
        public final String path;
        public final String name;
        public final long size;
        public final long durationMs;
        public final long dateAdded;

        TelegramLocalFile(String path, String name, long size, long durationMs, long dateAdded) {
            this.path = path;
            this.name = name;
            this.size = size;
            this.durationMs = durationMs;
            this.dateAdded = dateAdded;
        }

        public String getTitle() {
            int dot = name.lastIndexOf('.');
            return dot > 0 ? name.substring(0, dot) : name;
        }

        public String getArtist() {
            // Telegram usually names files like "Artist - Title.mp3"
            String lower = name.toLowerCase();
            for (String sep : new String[]{" - ", " – ", " — "}) {
                int idx = lower.lastIndexOf(sep);
                if (idx > 0 && idx < name.length() - 1) {
                    int dot = name.lastIndexOf('.');
                    if (dot > idx) {
                        return name.substring(0, idx).trim();
                    }
                }
            }
            return "";
        }

        public String getDisplayName() {
            String artist = getArtist();
            if (!artist.isEmpty()) {
                return artist + " - " + getTitle();
            }
            return getTitle();
        }

        public String getDurationLabel() {
            if (durationMs <= 0) return "";
            int totalSec = (int) (durationMs / 1000);
            int min = totalSec / 60;
            int sec = totalSec % 60;
            return String.format(java.util.Locale.US, "%d:%02d", min, sec);
        }

        public String getFileSizeLabel() {
            if (size <= 0) return "";
            double kb = size / 1024.0;
            if (kb < 1024) return String.format(java.util.Locale.US, "%.0f KB", kb);
            double mb = kb / 1024.0;
            if (mb < 1024) return String.format(java.util.Locale.US, "%.1f MB", mb);
            return String.format(java.util.Locale.US, "%.1f GB", mb / 1024.0);
        }
    }
}
