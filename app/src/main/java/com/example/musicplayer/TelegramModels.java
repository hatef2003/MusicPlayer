package com.example.musicplayer;

import java.util.ArrayList;
import java.util.List;

/**
 * Data models for Telegram forum browsing.
 */
public class TelegramModels {

    /** A Telegram group/chat the bot belongs to. */
    public static class TelegramGroup {
        public final String chatId;
        public final String title;
        public final String type;
        public final List<TelegramTopic> topics = new ArrayList<>();

        public TelegramGroup(String chatId, String title, String type) {
            this.chatId = chatId;
            this.title = title;
            this.type = type;
        }

        @Override
        public String toString() { return title; }
    }

    /** A forum topic within a group. Acts as a "playlist". */
    public static class TelegramTopic {
        public final int threadId;
        public String name;
        public final List<TelegramTrack> tracks = new ArrayList<>();

        public TelegramTopic(int threadId, String name) {
            this.threadId = threadId;
            this.name = name;
        }

        @Override
        public String toString() { return name; }
    }

    /** A music track (audio/document) from a Telegram message. */
    public static class TelegramTrack {
        public final long messageId;
        public final String fileName;
        public final String title;
        public final String performer;
        public final long fileSize;
        public final String fileId;
        public final String mimeType;
        public final String caption;
        public final int duration; // seconds, from audio duration field

        public TelegramTrack(long messageId, String fileName, String title, String performer,
                             long fileSize, String fileId, String mimeType, String caption, int duration) {
            this.messageId = messageId;
            this.fileName = fileName != null ? fileName : "unknown";
            this.title = title != null && !title.isEmpty() ? title : fileName;
            this.performer = performer != null ? performer : "";
            this.fileSize = fileSize;
            this.fileId = fileId;
            this.mimeType = mimeType;
            this.caption = caption;
            this.duration = duration;
        }

        public String getDisplayName() {
            if (!performer.isEmpty()) return performer + " - " + title;
            return title;
        }

        public String getFileSizeLabel() {
            if (fileSize <= 0) return "";
            if (fileSize < 1024) return fileSize + " B";
            if (fileSize < 1024 * 1024) return String.format(java.util.Locale.US, "%.1f KB", fileSize / 1024.0);
            if (fileSize < 1024L * 1024 * 1024) return String.format(java.util.Locale.US, "%.1f MB", fileSize / (1024.0 * 1024));
            return String.format(java.util.Locale.US, "%.2f GB", fileSize / (1024.0 * 1024 * 1024));
        }

        public String getDurationLabel() {
            if (duration <= 0) return "";
            int mins = duration / 60;
            int secs = duration % 60;
            return String.format(java.util.Locale.US, "%d:%02d", mins, secs);
        }
    }

    /** Current state of a download for a track. */
    public enum DownloadState {
        IDLE,
        DOWNLOADING,
        SAVED,
        TOO_LARGE,
        ERROR
    }
}
