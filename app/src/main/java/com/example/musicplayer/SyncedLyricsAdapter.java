package com.example.musicplayer;

import android.graphics.Color;
import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * Displays lyrics line-by-line in a RecyclerView.
 * For synced (LRC) lyrics: highlights the current line and supports tap-to-seek.
 * For plain lyrics: just displays text (no highlight, no tap).
 */
public class SyncedLyricsAdapter extends RecyclerView.Adapter<SyncedLyricsAdapter.LyricViewHolder> {

    public interface OnLineTapListener {
        void onLineTapped(long timestampMs);
    }

    private List<LyricLine> lines = new ArrayList<>();
    private int currentLineIndex = -1;
    private OnLineTapListener tapListener;
    private boolean isSynced = false;
    private boolean syncEnabled = true;

    /** Color for the highlighted (active) line */
    private int highlightColor = 0xFF00FF00;      // green
    /** Color for normal (inactive) lines */
    private int normalColor = 0xFFAAAAAA;          // grey
    /** Color for already-passed lines */
    private int pastColor = 0xFF666666;            // dimmer grey

    public void setOnLineTapListener(OnLineTapListener listener) {
        this.tapListener = listener;
    }

    public void setColors(int highlight, int normal, int past) {
        this.highlightColor = highlight;
        this.normalColor = normal;
        this.pastColor = past;
        notifyDataSetChanged();
    }

    public void setLines(List<LyricLine> newLines) {
        this.lines = newLines != null ? newLines : new ArrayList<>();
        this.isSynced = false;
        for (LyricLine ll : this.lines) {
            if (ll.isSynced()) {
                this.isSynced = true;
                break;
            }
        }
        this.currentLineIndex = -1;
        notifyDataSetChanged();
    }

    /**
     * Update which line is currently active based on playback position.
     * Returns the index of the current line (or -1 if none).
     */
    public int updateCurrentLine(int positionMs) {
        if (!isSynced() || lines.isEmpty()) return -1;

        int newIndex = -1;
        for (int i = lines.size() - 1; i >= 0; i--) {
            if (lines.get(i).timestampMs <= positionMs) {
                newIndex = i;
                break;
            }
        }

        if (newIndex != currentLineIndex) {
            int oldIndex = currentLineIndex;
            currentLineIndex = newIndex;
            if (oldIndex >= 0) notifyItemChanged(oldIndex);
            if (newIndex >= 0) notifyItemChanged(newIndex);
        }
        return currentLineIndex;
    }

    public int getCurrentLineIndex() {
        return currentLineIndex;
    }

    public boolean isSynced() {
        return isSynced && syncEnabled;
    }

    public boolean hasSyncData() {
        return isSynced;
    }

    public void setSyncEnabled(boolean enabled) {
        this.syncEnabled = enabled;
        if (!enabled) currentLineIndex = -1;
        notifyDataSetChanged();
    }

    public boolean isSyncEnabled() {
        return syncEnabled;
    }

    @NonNull
    @Override
    public LyricViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        TextView tv = new TextView(parent.getContext());
        tv.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        tv.setPadding(16, 10, 16, 10);
        tv.setTextSize(14f);
        tv.setTypeface(Typeface.MONOSPACE);
        return new LyricViewHolder(tv);
    }

    @Override
    public void onBindViewHolder(@NonNull LyricViewHolder holder, int position) {
        LyricLine line = lines.get(position);
        holder.textView.setText(line.text);

        // Set color, size, padding based on state
        if (isSynced) {
            if (position == currentLineIndex) {
                holder.textView.setText("> " + line.text);
                holder.textView.setTextColor(highlightColor);
                holder.textView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
                holder.textView.setTextSize(24f);
                holder.textView.setAlpha(1.0f);
                holder.textView.setPadding(12, 14, 12, 14);
            } else if (position < currentLineIndex) {
                holder.textView.setText("  " + line.text);
                holder.textView.setTextColor(pastColor);
                holder.textView.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL);
                holder.textView.setTextSize(16f);
                holder.textView.setAlpha(0.4f);
                holder.textView.setPadding(12, 8, 12, 8);
            } else {
                holder.textView.setText("  " + line.text);
                holder.textView.setTextColor(normalColor);
                holder.textView.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL);
                holder.textView.setTextSize(16f);
                holder.textView.setAlpha(0.7f);
                holder.textView.setPadding(12, 8, 12, 8);
            }
        } else {
            // Plain lyrics — no highlight, no tap
            holder.textView.setTextColor(normalColor);
            holder.textView.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL);
            holder.textView.setTextSize(14f);
            holder.textView.setAlpha(1.0f);
            holder.textView.setPadding(16, 10, 16, 10);
        }

        // Tap-to-seek for synced lyrics
        if (isSynced && line.isSynced() && tapListener != null) {
            holder.itemView.setOnClickListener(v -> tapListener.onLineTapped(line.timestampMs));
            holder.itemView.setClickable(true);
        } else {
            holder.itemView.setOnClickListener(null);
            holder.itemView.setClickable(false);
        }
    }

    @Override
    public int getItemCount() {
        return lines.size();
    }

    static class LyricViewHolder extends RecyclerView.ViewHolder {
        TextView textView;

        LyricViewHolder(@NonNull View itemView) {
            super(itemView);
            textView = (TextView) itemView;
        }
    }
}
