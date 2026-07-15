package com.example.musicplayer;

import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.musicplayer.model.Song;

import java.util.List;

public class SongAdapter extends RecyclerView.Adapter<SongAdapter.SongViewHolder> {

    public interface OnSongClickListener {
        void onSongClicked(Song song);
    }

    public interface OnSongLongClickListener {
        void onSongLongClicked(Song song);
    }

    private final List<Song> songs;
    private final OnSongClickListener listener;
    private OnSongLongClickListener longClickListener;
    private long playingSongId = -1;
    private int accentColor = -1;

    public SongAdapter(List<Song> songs, OnSongClickListener listener) {
        this.songs = songs;
        this.listener = listener;
    }

    public void setOnLongClickListener(OnSongLongClickListener longClickListener) {
        this.longClickListener = longClickListener;
    }

    public void setPlayingSongId(long songId) {
        if (this.playingSongId != songId) {
            this.playingSongId = songId;
            notifyDataSetChanged();
        }
    }

    private int getAccentColor(View v) {
        if (accentColor == -1) {
            accentColor = v.getContext().getResources().getColor(R.color.terminal_green);
        }
        return accentColor;
    }

    @NonNull
    @Override
    public SongViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_song, parent, false);
        return new SongViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull SongViewHolder holder, int position) {
        Song song = songs.get(position);
        boolean isPlaying = song.getId() == playingSongId;

        holder.trackNumber.setText(String.format(java.util.Locale.US, "%02d", position + 1));
        holder.title.setText(song.getTitle());
        holder.artist.setText(song.getArtist());
        holder.duration.setText(song.getDurationLabel());

        if (isPlaying) {
            // Currently playing: green border card, green title, ▶ indicator
            int accent = getAccentColor(holder.itemView);
            holder.title.setTextColor(accent);
            holder.trackNumber.setText("▶");
            holder.trackNumber.setTextColor(accent);
            holder.duration.setTextColor(accent);
            holder.itemView.setBackgroundResource(R.drawable.bg_song_card_playing);
        } else {
            // Normal state: subtle border card
            holder.title.setTextColor(holder.itemView.getContext().getResources().getColor(R.color.text_primary));
            holder.trackNumber.setTextColor(holder.itemView.getContext().getResources().getColor(R.color.text_secondary));
            holder.duration.setTextColor(holder.itemView.getContext().getResources().getColor(R.color.terminal_green));
            holder.itemView.setBackgroundResource(R.drawable.bg_song_card);
        }

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onSongClicked(song);
        });
        holder.itemView.setOnLongClickListener(v -> {
            if (longClickListener != null) longClickListener.onSongLongClicked(song);
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return songs.size();
    }

    static class SongViewHolder extends RecyclerView.ViewHolder {
        TextView trackNumber;
        TextView title;
        TextView artist;
        TextView duration;

        SongViewHolder(@NonNull View itemView) {
            super(itemView);
            trackNumber = itemView.findViewById(R.id.trackNumber);
            title = itemView.findViewById(R.id.songTitle);
            artist = itemView.findViewById(R.id.songArtist);
            duration = itemView.findViewById(R.id.songDuration);
        }
    }
}
