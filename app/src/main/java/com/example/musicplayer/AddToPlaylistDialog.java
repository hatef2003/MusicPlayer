package com.example.musicplayer;

import android.app.Dialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.musicplayer.model.Playlist;
import com.example.musicplayer.model.Song;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import java.util.ArrayList;
import java.util.List;

/**
 * Bottom sheet dialog for adding a song to a playlist.
 */
public class AddToPlaylistDialog extends BottomSheetDialogFragment {

    private static final String ARG_SONG_ID = "song_id";
    private static final String ARG_SONG_TITLE = "song_title";

    private long songId;
    private String songTitle;
    private PlaylistManager playlistManager;

    public static AddToPlaylistDialog newInstance(Song song) {
        AddToPlaylistDialog dialog = new AddToPlaylistDialog();
        Bundle args = new Bundle();
        args.putLong(ARG_SONG_ID, song.getId());
        args.putString(ARG_SONG_TITLE, song.getTitle());
        dialog.setArguments(args);
        return dialog;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            songId = getArguments().getLong(ARG_SONG_ID);
            songTitle = getArguments().getString(ARG_SONG_TITLE, "");
        }
        playlistManager = PlaylistManager.getInstance(requireContext());
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.dialog_add_to_playlist, container, false);

        RecyclerView recycler = view.findViewById(R.id.dialogPlaylistRecycler);
        TextView emptyText = view.findViewById(R.id.dialogEmptyText);
        View createBtn = view.findViewById(R.id.createPlaylistBtn);

        recycler.setLayoutManager(new LinearLayoutManager(getContext()));

        List<Playlist> playlists = playlistManager.getAllPlaylists();

        // Build a simple adapter for the dialog
        List<String> names = new ArrayList<>();
        for (Playlist p : playlists) {
            boolean alreadyAdded = p.containsSong(songId);
            names.add(p.getName() + (alreadyAdded ? " ✓" : " (" + p.getSongCount() + " songs)"));
        }

        DialogListAdapter listAdapter = new DialogListAdapter(playlists, names, playlist -> {
            if (playlist.containsSong(songId)) {
                Toast.makeText(getContext(), "Already in \"" + playlist.getName() + "\"", Toast.LENGTH_SHORT).show();
            } else {
                playlistManager.addSongToPlaylist(playlist.getId(), songId);
                Toast.makeText(getContext(), "Added to \"" + playlist.getName() + "\"", Toast.LENGTH_SHORT).show();
                dismiss();
            }
        });
        recycler.setAdapter(listAdapter);

        emptyText.setVisibility(playlists.isEmpty() ? View.VISIBLE : View.GONE);
        recycler.setVisibility(playlists.isEmpty() ? View.GONE : View.VISIBLE);

        createBtn.setOnClickListener(v -> {
            EditText input = new EditText(getContext());
            input.setHint("Playlist name");
            input.setPadding(48, 32, 48, 16);

            new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle("Create Playlist")
                    .setView(input)
                    .setPositiveButton("Create", (dialog, which) -> {
                        String name = input.getText().toString().trim();
                        if (!name.isEmpty()) {
                            Playlist newPlaylist = playlistManager.createPlaylist(name);
                            playlistManager.addSongToPlaylist(newPlaylist.getId(), songId);
                            Toast.makeText(getContext(), "Added to \"" + name + "\"", Toast.LENGTH_SHORT).show();
                            dismiss();
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });

        // Set the title
        TextView title = view.findViewById(R.id.dialogTitle);
        if (title != null) {
            title.setText("Add \"" + songTitle + "\" to…");
        }

        return view;
    }

    /**
     * Simple inline adapter for the playlist list in the dialog.
     */
    private static class DialogListAdapter extends RecyclerView.Adapter<DialogListAdapter.VH> {

        interface OnItemClick { void onClick(Playlist p); }

        private final List<Playlist> playlists;
        private final List<String> labels;
        private final OnItemClick clickListener;

        DialogListAdapter(List<Playlist> playlists, List<String> labels, OnItemClick clickListener) {
            this.playlists = playlists;
            this.labels = labels;
            this.clickListener = clickListener;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            TextView tv = new TextView(parent.getContext());
            tv.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            tv.setPadding(48, 28, 48, 28);
            tv.setTextSize(15f);
            tv.setTextColor(parent.getContext().getResources().getColor(R.color.text_primary));
            return new VH(tv);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            TextView tv = (TextView) holder.itemView;
            tv.setText(labels.get(position));
            tv.setOnClickListener(v -> clickListener.onClick(playlists.get(position)));
        }

        @Override
        public int getItemCount() { return playlists.size(); }

        static class VH extends RecyclerView.ViewHolder {
            VH(@NonNull View itemView) { super(itemView); }
        }
    }
}
