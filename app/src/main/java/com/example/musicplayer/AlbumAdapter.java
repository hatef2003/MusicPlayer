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

import java.util.List;

public class AlbumAdapter extends RecyclerView.Adapter<AlbumAdapter.AlbumViewHolder> {

    public interface OnAlbumClickListener {
        void onAlbumClicked(Album album);
    }

    private final List<Album> albums;
    private final OnAlbumClickListener listener;

    public AlbumAdapter(List<Album> albums, OnAlbumClickListener listener) {
        this.albums = albums;
        this.listener = listener;
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
        holder.count.setText(album.getSongCount() + " tracks");
        Glide.with(holder.itemView.getContext())
                .load(MusicRepository.getAlbumArtUri(album.getId()))
                .placeholder(R.drawable.ic_album)
                .error(R.drawable.ic_album)
                .centerCrop()
                .into(holder.albumArt);
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onAlbumClicked(album);
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

        AlbumViewHolder(@NonNull View itemView) {
            super(itemView);
            albumArt = itemView.findViewById(R.id.albumArt);
            name = itemView.findViewById(R.id.albumName);
            artist = itemView.findViewById(R.id.albumArtist);
            count = itemView.findViewById(R.id.albumCount);
        }
    }
}
