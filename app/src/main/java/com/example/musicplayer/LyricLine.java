package com.example.musicplayer;

/**
 * A single line of lyrics, optionally with an LRC timestamp for synced scrolling.
 * timestampMs is -1 for plain (non-synced) lyrics.
 */
public class LyricLine {
    public final long timestampMs;
    public final String text;

    public LyricLine(long timestampMs, String text) {
        this.timestampMs = timestampMs;
        this.text = text;
    }

    public boolean isSynced() {
        return timestampMs >= 0;
    }
}
