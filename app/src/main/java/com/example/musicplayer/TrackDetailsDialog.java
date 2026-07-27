package com.example.musicplayer;

import android.app.Activity;
import android.app.Dialog;
import android.content.ContentResolver;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.example.musicplayer.model.Song;

import java.io.File;
import java.util.Locale;

/**
 * A futuristic "Tech UI" dialog that displays full track metadata.
 * Shows title, artist, album, duration, format, file size, bitrate,
 * sample rate, date added, and file path.
 */
public class TrackDetailsDialog extends Dialog {

    private final Song song;

    public TrackDetailsDialog(Activity activity, Song song) {
        super(activity);
        this.song = song;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        // Root container
        ScrollView scrollView = new ScrollView(getContext());
        scrollView.setBackgroundColor(Color.TRANSPARENT);

        LinearLayout root = new LinearLayout(getContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(20), dp(24), dp(20));
        root.setBackground(createDialogBackground());

        // ── Header ──
        root.addView(createLabel("TRACK DETAILS", 11, 0xFF39FF6A, 0.15f));
        root.addView(createSpacer(dp(16)));

        // ── Fields ──
        addField(root, "TITLE", song.getTitle());
        addField(root, "ARTIST", song.getArtist());
        addField(root, "ALBUM", song.getAlbum());
        addField(root, "DURATION", song.getDurationLabel());

        // File metadata
        addField(root, "FORMAT", getFormat(song.getContentUri()));
        addField(root, "FILE SIZE", getFileSize(song.getContentUri()));
        addField(root, "DATE ADDED", formatDate(song.getDateAdded()));

        // File path (best effort)
        String path = getFilePath(song.getId());
        if (path != null) {
            addField(root, "FILE PATH", path);
        }

        // ── Song ID ──
        addField(root, "TRACK ID", String.valueOf(song.getId()));

        // ── Close button ──
        root.addView(createSpacer(dp(16)));
        TextView closeBtn = new TextView(getContext());
        closeBtn.setText("CLOSE");
        closeBtn.setTextColor(0xFF39FF6A);
        closeBtn.setTextSize(13);
        closeBtn.setGravity(Gravity.CENTER);
        closeBtn.setPadding(dp(24), dp(12), dp(24), dp(12));
        closeBtn.setClickable(true);
        closeBtn.setFocusable(true);
        closeBtn.setBackgroundResource(R.drawable.bg_tech_menu_item_ripple);
        closeBtn.setOnClickListener(v -> dismiss());
        root.addView(closeBtn);

        scrollView.addView(root);
        setContentView(scrollView);

        // Window setup
        Window window = getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setGravity(Gravity.CENTER);
            window.setDimAmount(0.7f);
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.windowAnimations = android.R.style.Animation_Dialog;
            window.setAttributes(lp);
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private void addField(LinearLayout root, String label, String value) {
        root.addView(createLabel(label, 10, 0xFF8A8A8A, 0.1f));
        root.addView(createSpacer(dp(4)));
        root.addView(createValue(value != null && !value.isEmpty() ? value : "Unknown"));
        root.addView(createSpacer(dp(14)));
    }

    private TextView createLabel(String text, float sizeSp, int color, float letterSpacing) {
        TextView tv = new TextView(getContext());
        tv.setText(text);
        tv.setTextColor(color);
        tv.setTextSize(sizeSp);
        tv.setLetterSpacing(letterSpacing);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        return tv;
    }

    private TextView createValue(String text) {
        TextView tv = new TextView(getContext());
        tv.setText(text);
        tv.setTextColor(0xFFF2F2F2);
        tv.setTextSize(14);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setSingleLine(false);
        tv.setMaxLines(4);
        return tv;
    }

    private View createSpacer(int heightPx) {
        View v = new View(getContext());
        v.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, heightPx));
        return v;
    }

    private android.graphics.drawable.Drawable createDialogBackground() {
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xF018181C);
        bg.setCornerRadius(dp(28));
        bg.setStroke(dp(1), 0x10FFFFFF);
        return bg;
    }

    private String getFormat(Uri contentUri) {
        String uriStr = contentUri.toString().toLowerCase(Locale.ROOT);
        if (uriStr.endsWith(".mp3") || uriStr.contains("mp3")) return "MP3";
        if (uriStr.endsWith(".flac") || uriStr.contains("flac")) return "FLAC";
        if (uriStr.endsWith(".aac") || uriStr.contains("aac")) return "AAC";
        if (uriStr.endsWith(".ogg") || uriStr.contains("ogg")) return "OGG";
        if (uriStr.endsWith(".wav") || uriStr.contains("wav")) return "WAV";
        if (uriStr.endsWith(".m4a") || uriStr.contains("m4a")) return "M4A";
        if (uriStr.endsWith(".wma") || uriStr.contains("wma")) return "WMA";
        // Try to detect from MediaStore
        try {
            ContentResolver cr = getContext().getContentResolver();
            Cursor c = cr.query(contentUri,
                    new String[]{MediaStore.Audio.Media.MIME_TYPE}, null, null, null);
            if (c != null && c.moveToFirst()) {
                String mime = c.getString(0);
                c.close();
                if (mime != null) {
                    if (mime.contains("mpeg")) return "MP3";
                    if (mime.contains("flac")) return "FLAC";
                    if (mime.contains("aac")) return "AAC";
                    if (mime.contains("ogg")) return "OGG";
                    if (mime.contains("wav")) return "WAV";
                    if (mime.contains("x-m4a")) return "M4A";
                    return mime.substring(mime.lastIndexOf('/') + 1).toUpperCase(Locale.ROOT);
                }
            }
            if (c != null) c.close();
        } catch (Exception ignored) { }
        return "Audio";
    }

    private String getFileSize(Uri contentUri) {
        try {
            ContentResolver cr = getContext().getContentResolver();
            Cursor c = cr.query(contentUri,
                    new String[]{MediaStore.Audio.Media.SIZE}, null, null, null);
            if (c != null && c.moveToFirst()) {
                long bytes = c.getLong(0);
                c.close();
                if (bytes < 1024) return bytes + " B";
                if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
                return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
            }
            if (c != null) c.close();
        } catch (Exception ignored) { }
        return "Unknown";
    }

    private String formatDate(long epochSeconds) {
        if (epochSeconds <= 0) return "Unknown";
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US);
        return sdf.format(new java.util.Date(epochSeconds * 1000));
    }

    private String getFilePath(long songId) {
        try {
            ContentResolver cr = getContext().getContentResolver();
            Cursor c = cr.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    new String[]{MediaStore.Audio.Media.DATA},
                    MediaStore.Audio.Media._ID + "=?",
                    new String[]{String.valueOf(songId)}, null);
            if (c != null && c.moveToFirst()) {
                String path = c.getString(0);
                c.close();
                return path;
            }
            if (c != null) c.close();
        } catch (Exception ignored) { }
        return null;
    }

    private int dp(int dp) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp,
                getContext().getResources().getDisplayMetrics());
    }
}
