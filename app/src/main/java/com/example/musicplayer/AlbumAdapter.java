package com.example.musicplayer;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.example.musicplayer.model.Album;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class AlbumAdapter extends RecyclerView.Adapter<AlbumAdapter.AlbumViewHolder> {

    public interface OnAlbumClickListener {
        void onAlbumClicked(Album album);
    }

    public interface OnAlbumMenuListener {
        void onPlayAll(Album album);
        void onShufflePlay(Album album);
        void onGoToArtist(String artist);
    }

    private final List<Album> albums;
    private final OnAlbumClickListener listener;
    private OnAlbumMenuListener menuListener;
    private long playingAlbumId = -1;

    public AlbumAdapter(List<Album> albums, OnAlbumClickListener listener) {
        this.albums = albums;
        this.listener = listener;
    }

    public void setOnAlbumMenuListener(OnAlbumMenuListener menuListener) {
        this.menuListener = menuListener;
    }

    public void setPlayingAlbumId(long albumId) {
        if (this.playingAlbumId != albumId) {
            this.playingAlbumId = albumId;
            notifyDataSetChanged();
        }
    }

    @NonNull
    @Override
    public AlbumViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_album_card, parent, false);
        return new AlbumViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull AlbumViewHolder holder, int position) {
        Album album = albums.get(position);

        holder.name.setText(album.getName());
        holder.artist.setText(album.getArtist());
        holder.count.setText(String.format(Locale.US, "TRACKS %d", album.getSongCount()));

        // Format duration
        long totalMs = album.getTotalDuration();
        int totalMin = (int) (totalMs / 60000);
        holder.duration.setText(String.format(Locale.US, "%d min", totalMin));

        // Album art
        Glide.with(holder.itemView.getContext())
                .load(MusicRepository.getAlbumArtUri(album.getId()))
                .placeholder(R.drawable.ic_album)
                .error(R.drawable.ic_album)
                .centerCrop()
                .into(holder.albumArt);

        // PLAYING indicator
        boolean isPlaying = (album.getId() == playingAlbumId);
        holder.playingLabel.setVisibility(isPlaying ? View.VISIBLE : View.GONE);
        holder.itemView.setBackgroundResource(isPlaying
                ? R.drawable.bg_album_card_playing
                : R.drawable.bg_album_card);

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onAlbumClicked(album);
        });

        // Three-dot menu — Tech UI context menu
        holder.menuBtn.setOnClickListener(v -> {
            if (menuListener == null) return;
            new TechContextMenu.Builder(v.getContext())
                    .addItem(R.drawable.ic_menu_play_all, "Play All", () -> menuListener.onPlayAll(album))
                    .addItem(R.drawable.ic_menu_shuffle, "Shuffle Play", () -> menuListener.onShufflePlay(album))
                    .addSpacer()
                    .addItem(R.drawable.ic_menu_artist, "Go to Artist", () -> menuListener.onGoToArtist(album.getArtist()))
                    .show(v);
        });
    }

    @Override
    public int getItemCount() {
        return albums.size();
    }

    static class AlbumViewHolder extends RecyclerView.ViewHolder {
        ImageView albumArt;
        TextView name;
        TextView artist;
        TextView count;
        TextView duration;
        TextView playingLabel;
        TextView menuBtn;

        AlbumViewHolder(@NonNull View itemView) {
            super(itemView);
            albumArt = itemView.findViewById(R.id.albumArt);
            name = itemView.findViewById(R.id.albumName);
            artist = itemView.findViewById(R.id.albumArtist);
            count = itemView.findViewById(R.id.albumCount);
            duration = itemView.findViewById(R.id.albumDuration);
            playingLabel = itemView.findViewById(R.id.albumPlaying);
            menuBtn = itemView.findViewById(R.id.albumMenu);
        }
    }
}
