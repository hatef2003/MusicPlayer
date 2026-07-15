package com.example.musicplayer;

import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
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
        return new AlbumViewHolder(createAlbumCardView(parent));
    }

    /** Build the album card programmatically to bypass XML inflation issues. */
    private View createAlbumCardView(ViewGroup parent) {
        final float density = parent.getResources().getDisplayMetrics().density;

        // Root LinearLayout
        LinearLayout root = new LinearLayout(parent.getContext());
        root.setOrientation(LinearLayout.VERTICAL);
        int margin = (int) (8 * density);
        RecyclerView.LayoutParams rootLp = new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        rootLp.setMargins(margin, margin, margin, margin);
        root.setLayoutParams(rootLp);
        root.setBackgroundColor(ContextCompat.getColor(parent.getContext(), R.color.md_surface));
        int padding = (int) (12 * density);
        root.setPadding(padding, padding, padding, padding);

        // Album Art ImageView
        ImageView albumArt = new ImageView(parent.getContext());
        albumArt.setId(R.id.albumArt);
        LinearLayout.LayoutParams imgLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (120 * density));
        albumArt.setLayoutParams(imgLp);
        albumArt.setBackgroundColor(ContextCompat.getColor(parent.getContext(), R.color.md_primary));
        albumArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        albumArt.setImageResource(R.drawable.ic_album);
        albumArt.setContentDescription("Album art");
        root.addView(albumArt);

        // Album Name
        TextView albumName = new TextView(parent.getContext());
        albumName.setId(R.id.albumName);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nameLp.topMargin = (int) (8 * density);
        albumName.setLayoutParams(nameLp);
        albumName.setEllipsize(TextUtils.TruncateAt.END);
        albumName.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        albumName.setSingleLine(true);
        albumName.setTextColor(ContextCompat.getColor(parent.getContext(), R.color.text_primary));
        albumName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        root.addView(albumName);

        // Album Artist
        TextView albumArtist = new TextView(parent.getContext());
        albumArtist.setId(R.id.albumArtist);
        albumArtist.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        albumArtist.setEllipsize(TextUtils.TruncateAt.END);
        albumArtist.setTypeface(Typeface.MONOSPACE);
        albumArtist.setSingleLine(true);
        albumArtist.setTextColor(ContextCompat.getColor(parent.getContext(), R.color.text_secondary));
        albumArtist.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        root.addView(albumArtist);

        // Album Track Count
        TextView albumCount = new TextView(parent.getContext());
        albumCount.setId(R.id.albumCount);
        LinearLayout.LayoutParams countLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        countLp.topMargin = (int) (4 * density);
        albumCount.setLayoutParams(countLp);
        albumCount.setTypeface(Typeface.MONOSPACE);
        albumCount.setTextColor(ContextCompat.getColor(parent.getContext(), R.color.md_accent));
        albumCount.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        root.addView(albumCount);

        return root;
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
