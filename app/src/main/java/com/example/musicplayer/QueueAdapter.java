package com.example.musicplayer;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.example.musicplayer.model.Song;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class QueueAdapter extends RecyclerView.Adapter<QueueAdapter.QueueHolder> {

    private final List<Song> songs = new ArrayList<>();
    private int currentIndex = -1;
    private int accentColor = -1;

    private OnQueueActionListener listener;
    private ItemTouchHelper touchHelper;

    public interface OnQueueActionListener {
        void onSongClick(int position);
        void onSongRemoved(int position);
        void onItemMoved(int from, int to);
    }

    public void setOnQueueActionListener(OnQueueActionListener listener) {
        this.listener = listener;
    }

    public void setTouchHelper(ItemTouchHelper helper) {
        this.touchHelper = helper;
    }

    public void setQueue(List<Song> queue, int currentIdx) {
        songs.clear();
        songs.addAll(queue);
        currentIndex = currentIdx;
        notifyDataSetChanged();
    }

    public void setCurrentIndex(int index) {
        int old = currentIndex;
        currentIndex = index;
        if (old >= 0 && old < songs.size()) notifyItemChanged(old);
        if (index >= 0 && index < songs.size()) notifyItemChanged(index);
    }

    public List<Song> getSongs() {
        return new ArrayList<>(songs);
    }

    @NonNull
    @Override
    public QueueHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_queue, parent, false);
        return new QueueHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull QueueHolder h, int position) {
        if (position < 0 || position >= songs.size()) return;
        Song song = songs.get(position);

        // Index
        h.index.setText(String.format(Locale.US, "%02d", position + 1));

        // Title and artist
        h.title.setText(song.getTitle());
        h.artist.setText(song.getArtist());
        h.duration.setText(song.getDurationLabel());

        // Resolve accent color once
        if (accentColor == -1) {
            accentColor = ContextCompat.getColor(h.itemView.getContext(), R.color.terminal_green);
        }

        boolean isCurrent = (position == currentIndex);

        // Highlight currently playing song
        if (isCurrent) {
            h.title.setTextColor(accentColor);
            h.index.setTextColor(accentColor);
            h.index.setText("▶");
            h.itemView.setBackgroundColor(Color.parseColor("#1539FF6A"));
        } else {
            h.title.setTextColor(ContextCompat.getColor(h.itemView.getContext(), R.color.text_primary));
            h.index.setTextColor(ContextCompat.getColor(h.itemView.getContext(), R.color.text_secondary));
            h.itemView.setBackgroundColor(Color.TRANSPARENT);
        }

        // Click to play from this position
        h.itemView.setOnClickListener(v -> {
            int pos = h.getBindingAdapterPosition();
            if (pos != RecyclerView.NO_POSITION && listener != null) {
                listener.onSongClick(pos);
            }
        });

        // Remove button
        h.removeBtn.setOnClickListener(v -> {
            int pos = h.getBindingAdapterPosition();
            if (pos != RecyclerView.NO_POSITION && listener != null) {
                listener.onSongRemoved(pos);
            }
        });

        // Drag handle - start drag on touch
        h.dragHandle.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN && touchHelper != null) {
                touchHelper.startDrag(h);
            }
            return false;
        });
    }

    @Override
    public int getItemCount() {
        return songs.size();
    }

    /**
     * Called by ItemTouchHelper.Callback when an item is moved via drag.
     */
    public boolean onItemMove(int fromPosition, int toPosition) {
        if (fromPosition < 0 || toPosition < 0 ||
                fromPosition >= songs.size() || toPosition >= songs.size()) return false;

        Collections.swap(songs, fromPosition, toPosition);

        // Track the currently playing song
        if (currentIndex == fromPosition) {
            currentIndex = toPosition;
        } else if (fromPosition < currentIndex && toPosition >= currentIndex) {
            currentIndex--;
        } else if (fromPosition > currentIndex && toPosition <= currentIndex) {
            currentIndex++;
        }

        notifyItemMoved(fromPosition, toPosition);
        return true;
    }

    static class QueueHolder extends RecyclerView.ViewHolder {
        TextView dragHandle, index, title, artist, duration, removeBtn;

        QueueHolder(@NonNull View itemView) {
            super(itemView);
            dragHandle = itemView.findViewById(R.id.qDragHandle);
            index = itemView.findViewById(R.id.qIndex);
            title = itemView.findViewById(R.id.qTitle);
            artist = itemView.findViewById(R.id.qArtist);
            duration = itemView.findViewById(R.id.qDuration);
            removeBtn = itemView.findViewById(R.id.qRemove);
        }
    }
}
