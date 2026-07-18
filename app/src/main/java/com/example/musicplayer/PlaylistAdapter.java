package com.example.musicplayer;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.musicplayer.model.Playlist;
import com.example.musicplayer.model.Song;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * RecyclerView adapter for displaying playlists.
 */
public class PlaylistAdapter extends RecyclerView.Adapter<PlaylistAdapter.PlaylistViewHolder> {

    public interface OnPlaylistClickListener {
        void onPlaylistClick(Playlist playlist);
        void onPlaylistLongClick(Playlist playlist);
    }

    private List<Playlist> playlists = new ArrayList<>();
    private final List<Song> allSongs;
    private OnPlaylistClickListener listener;

    public PlaylistAdapter(List<Song> allSongs) {
        this.allSongs = allSongs;
    }

    public void setOnPlaylistClickListener(OnPlaylistClickListener listener) {
        this.listener = listener;
    }

    public void setPlaylists(List<Playlist> playlists) {
        this.playlists = playlists;
        notifyDataSetChanged();
    }

    public List<Playlist> getPlaylists() {
        return playlists;
    }

    @NonNull
    @Override
    public PlaylistViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_playlist, parent, false);
        return new PlaylistViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull PlaylistViewHolder holder, int position) {
        Playlist playlist = playlists.get(position);
        holder.name.setText(playlist.getName());
        int count = playlist.getSongCount();
        holder.info.setText(count == 1 ? "1 song" : count + " songs");

        // Calculate total duration
        long totalDuration = 0;
        for (long songId : playlist.getSongIds()) {
            for (Song s : allSongs) {
                if (s.getId() == songId) {
                    totalDuration += s.getDuration();
                    break;
                }
            }
        }
        if (totalDuration > 0) {
            long secs = totalDuration / 1000;
            holder.duration.setText(String.format(Locale.US, "%d:%02d", secs / 60, secs % 60));
            holder.duration.setVisibility(View.VISIBLE);
        } else {
            holder.duration.setVisibility(View.GONE);
        }

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onPlaylistClick(playlist);
        });
        holder.itemView.setOnLongClickListener(v -> {
            if (listener != null) listener.onPlaylistLongClick(playlist);
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return playlists.size();
    }

    static class PlaylistViewHolder extends RecyclerView.ViewHolder {
        TextView name, info, duration;

        PlaylistViewHolder(@NonNull View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.playlistName);
            info = itemView.findViewById(R.id.playlistInfo);
            duration = itemView.findViewById(R.id.playlistDuration);
        }
    }
}
