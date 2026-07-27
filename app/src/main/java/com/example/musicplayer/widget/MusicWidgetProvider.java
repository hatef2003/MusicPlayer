package com.example.musicplayer.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.widget.RemoteViews;

import com.example.musicplayer.MusicService;
import com.example.musicplayer.NowPlayingActivity;
import com.example.musicplayer.R;
import com.example.musicplayer.SettingsManager;

import java.util.Locale;

/**
 * Home-screen widget for the 4x2 music player.
 *
 * <p><b>Communication:</b>
 * <ul>
 *   <li>Widget -> Service: {@link PendingIntent} with {@link MusicService#ACTION_*} strings.</li>
 *   <li>Service -> Widget: {@link MusicService} calls {@link #updateAllWidgets} on state changes
 *       and {@link #updateProgress} every second while playing.</li>
 * </ul>
 *
 * <p><b>Layout:</b> Terminal/hacker aesthetic -- dark background (#101010),
 * green accent, album art, text + real progress bar, prev / play-pause / next controls.</p>
 */
public class MusicWidgetProvider extends AppWidgetProvider {

    private static final String TAG = "MusicWidgetProvider";

    // Cached state so progress-only updates don't need to re-fetch metadata.
    private static final class WidgetState {
        boolean isPlaying;
        String title;
        String artist;
        Bitmap albumArt;
        int duration;
    }

    private static final WidgetState cachedState = new WidgetState();

    // -- Accent color map (matches SettingsManager.AccentColor) --
    private static int resolveAccentColor(Context context) {
        SettingsManager.AccentColor accent = new SettingsManager(context).getAccentColor();
        switch (accent) {
            case BLUE:   return Color.parseColor("#00A8FF");
            case ORANGE: return Color.parseColor("#FF9D00");
            case PURPLE: return Color.parseColor("#B829DD");
            case GREEN:
            default:     return Color.parseColor("#39FF6A");
        }
    }

    /**
     * Called by {@link MusicService} whenever playback state, song, or album art changes.
     */
    public static void updateAllWidgets(Context context,
                                        boolean isPlaying,
                                        String title,
                                        String artist,
                                        Bitmap albumArt,
                                        int position,
                                        int duration) {
        cachedState.isPlaying = isPlaying;
        cachedState.title = title;
        cachedState.artist = artist;
        cachedState.albumArt = albumArt;
        cachedState.duration = duration;

        AppWidgetManager mgr = AppWidgetManager.getInstance(context);
        ComponentName widget = new ComponentName(context, MusicWidgetProvider.class);
        int[] ids = mgr.getAppWidgetIds(widget);
        if (ids == null || ids.length == 0) return;

        RemoteViews views = buildRemoteViews(context, isPlaying, title, artist,
                albumArt, position, duration);
        for (int id : ids) {
            try {
                mgr.updateAppWidget(id, views);
            } catch (Exception e) {
                android.util.Log.e(TAG, "updateAllWidgets failed for id=" + id, e);
            }
        }
    }

    /**
     * Lightweight progress-only update. Uses cached metadata so the service can
     * call this frequently (every second) without rebuilding expensive state.
     */
    public static void updateProgress(Context context, int position) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(context);
        ComponentName widget = new ComponentName(context, MusicWidgetProvider.class);
        int[] ids = mgr.getAppWidgetIds(widget);
        if (ids == null || ids.length == 0) return;

        RemoteViews views = buildRemoteViews(context,
                cachedState.isPlaying,
                cachedState.title,
                cachedState.artist,
                cachedState.albumArt,
                position,
                cachedState.duration);
        for (int id : ids) {
            try {
                mgr.updateAppWidget(id, views);
            } catch (Exception e) {
                android.util.Log.e(TAG, "updateProgress failed for id=" + id, e);
            }
        }
    }

    /**
     * Build the {@link RemoteViews} for the 4x2 widget layout.
     */
    private static RemoteViews buildRemoteViews(Context context,
                                                boolean isPlaying,
                                                String title,
                                                String artist,
                                                Bitmap albumArt,
                                                int position,
                                                int duration) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_player);
        int accent = resolveAccentColor(context);

        // -- Song info --
        String safeTitle = (title != null && !title.isEmpty()) ? title : "No song playing";
        String safeArtist = (artist != null) ? artist : "";

        views.setTextViewText(R.id.widgetTitle, safeTitle);
        views.setTextViewText(R.id.widgetArtist, safeArtist);
        views.setTextColor(R.id.widgetTitle, accent);

        // -- Header: MEDIA    ● ACTIVE/IDLE --
        views.setTextViewText(R.id.widgetHeader,
                "MEDIA    \u25CF " + (isPlaying ? "ACTIVE" : "IDLE"));

        // -- Status: PLAYING/PAUSED below artist --
        views.setTextViewText(R.id.widgetStatus, isPlaying ? "PLAYING" : "PAUSED");
        views.setTextColor(R.id.widgetStatus, isPlaying ? accent : Color.parseColor("#666666"));

        // -- Album art --
        if (albumArt != null) {
            views.setImageViewBitmap(R.id.widgetAlbumArt, albumArt);
        } else {
            views.setImageViewResource(R.id.widgetAlbumArt, R.drawable.ic_album);
        }

        // -- Progress: text + real ProgressBar --
        if (duration > 0) {
            int progress = (int) ((long) position * 1000 / duration);
            progress = Math.max(0, Math.min(1000, progress));

            views.setTextViewText(R.id.widgetProgressText,
                    formatTime(position) + " " + unicodeProgressBar(position, duration) + " " + formatTime(duration));
            views.setProgressBar(R.id.widgetProgressBar, 1000, progress, false);
        } else {
            views.setTextViewText(R.id.widgetProgressText, "");
            views.setProgressBar(R.id.widgetProgressBar, 1000, 0, false);
        }

        // -- Prev / Next tint --
        views.setInt(R.id.widgetPrev, "setColorFilter", accent);
        views.setInt(R.id.widgetNext, "setColorFilter", accent);

        // -- Prev / Next PendingIntents --
        views.setOnClickPendingIntent(R.id.widgetPrev,
                servicePendingIntent(context, MusicService.ACTION_PREV));
        views.setOnClickPendingIntent(R.id.widgetNext,
                servicePendingIntent(context, MusicService.ACTION_NEXT));

        // -- Album art tap -> open app --
        Intent openApp = new Intent(context, NowPlayingActivity.class);
        openApp.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        views.setOnClickPendingIntent(R.id.widgetAlbumArt,
                PendingIntent.getActivity(context, 102, openApp, PendingIntent.FLAG_IMMUTABLE));

        // -- Play/Pause --
        views.setImageViewResource(R.id.widgetPlayPause,
                isPlaying ? R.drawable.ic_notif_pause : R.drawable.ic_notif_play);
        views.setInt(R.id.widgetPlayPause, "setColorFilter", accent);
        views.setOnClickPendingIntent(R.id.widgetPlayPause,
                servicePendingIntent(context, isPlaying ? MusicService.ACTION_PAUSE : MusicService.ACTION_RESUME));

        // -- Tap title/artist -> open NowPlayingActivity --
        views.setOnClickPendingIntent(R.id.widgetTitle,
                PendingIntent.getActivity(context, 100, openApp, PendingIntent.FLAG_IMMUTABLE));
        views.setOnClickPendingIntent(R.id.widgetArtist,
                PendingIntent.getActivity(context, 101, openApp, PendingIntent.FLAG_IMMUTABLE));

        return views;
    }

    /** Create a PendingIntent that starts {@link MusicService} with the given action. */
    private static PendingIntent servicePendingIntent(Context context, String action) {
        Intent intent = new Intent(context, MusicService.class);
        intent.setAction(action);
        int flags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        return PendingIntent.getService(context, action.hashCode(), intent, flags);
    }

    private static String formatTime(int ms) {
        int totalSeconds = ms / 1000;
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;
        return String.format(Locale.US, "%d:%02d", minutes, seconds);
    }

    /** Build a unicode progress bar like "███████░░░" (12 blocks wide). */
    private static String unicodeProgressBar(int position, int duration) {
        if (duration <= 0) return "";
        int blocks = 12;
        int filled = Math.max(0, Math.min(blocks, (int) ((long) position * blocks / duration)));
        StringBuilder sb = new StringBuilder(blocks * 2);
        for (int i = 0; i < filled; i++) sb.append('\u2588'); // full block
        for (int i = filled; i < blocks; i++) sb.append('\u2591'); // light shade
        return sb.toString();
    }

    // === AppWidgetProvider lifecycle ===

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        try {
            RemoteViews views = buildRemoteViews(context, false, null, null, null, 0, 0);
            for (int id : appWidgetIds) {
                appWidgetManager.updateAppWidget(id, views);
            }
        } catch (Exception e) {
            android.util.Log.e(TAG, "onUpdate failed", e);
        }
    }
}
