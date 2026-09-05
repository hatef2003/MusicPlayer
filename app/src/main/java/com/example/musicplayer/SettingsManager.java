package com.example.musicplayer;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import androidx.appcompat.app.AppCompatDelegate;

public class SettingsManager {

    private static final String PREFS_NAME = "music_player_settings";
    private static final String KEY_THEME = "theme";
    private static final String KEY_TRACKS_SORT = "tracks_sort";
    private static final String KEY_ACCENT_COLOR = "accent_color";
    private static final String KEY_AUDD_API_KEY = "audd_api_key";
    /** When true (default), the player pauses on audio focus loss (e.g. call answered) and resumes on gain. */
    private static final String KEY_PAUSE_ON_FOCUS_LOSS = "pause_on_focus_loss";
    /** When true (default), the player pauses when headphones are disconnected (ACTION_AUDIO_BECOMING_NOISY). */
    private static final String KEY_PAUSE_ON_BECOMING_NOISY = "pause_on_becoming_noisy";
    /** BotFather bot token (e.g. "123456789:AABBcc-DDeeff..." from @BotFather). */
    private static final String KEY_TELEGRAM_BOT_TOKEN = "telegram_bot_token";
    /** Telegram Bot API base URL (default https://api.telegram.org, or local Bot API server). */
    private static final String KEY_TELEGRAM_BASE_URL = "telegram_base_url";


    public enum Theme {
        SYSTEM("system"),
        LIGHT("light"),
        DARK("dark");

        private final String value;

        Theme(String value) { this.value = value; }

        public String getValue() { return value; }

        public static Theme from(String value) {
            for (Theme t : values()) {
                if (t.value.equalsIgnoreCase(value)) return t;
            }
            return SYSTEM;
        }
    }

    public enum TracksSort {
        TITLE("title"),
        ARTIST("artist"),
        ALBUM("album"),
        DURATION("duration"),
        DATE_ADDED("date_added");

        private final String value;

        TracksSort(String value) { this.value = value; }

        public String getValue() { return value; }

        public static TracksSort from(String value) {
            for (TracksSort s : values()) {
                if (s.value.equalsIgnoreCase(value)) return s;
            }
            return TITLE;
        }
    }


    public enum AccentColor {
        GREEN("green", R.style.Theme_MusicPlayer_Green),
        BLUE("blue", R.style.Theme_MusicPlayer_Blue),
        ORANGE("orange", R.style.Theme_MusicPlayer_Orange),
        PURPLE("purple", R.style.Theme_MusicPlayer_Purple);

        private final String value;
        private final int themeResId;

        AccentColor(String value, int themeResId) {
            this.value = value;
            this.themeResId = themeResId;
        }

        public String getValue() { return value; }

        public int getThemeResId() { return themeResId; }

        public static AccentColor from(String value) {
            for (AccentColor c : values()) {
                if (c.value.equalsIgnoreCase(value)) return c;
            }
            return GREEN;
        }
    }

    private final SharedPreferences prefs;

    public SettingsManager(Context context) {
        this.prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public Theme getTheme() {
        return Theme.from(prefs.getString(KEY_THEME, Theme.SYSTEM.value));
    }

    public void setTheme(Theme theme) {
        prefs.edit().putString(KEY_THEME, theme.value).apply();
    }

    public TracksSort getTracksSort() {
        return TracksSort.from(prefs.getString(KEY_TRACKS_SORT, TracksSort.TITLE.value));
    }

    public void setTracksSort(TracksSort sort) {
        prefs.edit().putString(KEY_TRACKS_SORT, sort.value).apply();
    }

    public AccentColor getAccentColor() {
        return AccentColor.from(prefs.getString(KEY_ACCENT_COLOR, AccentColor.GREEN.value));
    }

    public void setAccentColor(AccentColor accentColor) {
        prefs.edit().putString(KEY_ACCENT_COLOR, accentColor.value).apply();
    }

    public String getAuddApiKey() {
        return prefs.getString(KEY_AUDD_API_KEY, "");
    }

    public void setAuddApiKey(String key) {
        prefs.edit().putString(KEY_AUDD_API_KEY, key).apply();
    }

    /**
     * Whether the player should pause when audio focus is lost (e.g. an incoming/answered
     * call) and automatically resume when focus is regained.
     * Defaults to true. When false, the player ignores audio focus changes entirely.
     */
    public boolean shouldPauseOnFocusLoss() {
        return prefs.getBoolean(KEY_PAUSE_ON_FOCUS_LOSS, true);
    }

    public void setPauseOnFocusLoss(boolean enabled) {
        prefs.edit().putBoolean(KEY_PAUSE_ON_FOCUS_LOSS, enabled).apply();
    }

    /**
     * Whether the player should pause when headphones (or any audio output) are
     * disconnected, so music doesn't suddenly blast from the phone speaker.
     * Defaults to true. When false, playback continues on the new output.
     */
    public boolean shouldPauseOnBecomingNoisy() {
        return prefs.getBoolean(KEY_PAUSE_ON_BECOMING_NOISY, true);
    }

    public void setPauseOnBecomingNoisy(boolean enabled) {
        prefs.edit().putBoolean(KEY_PAUSE_ON_BECOMING_NOISY, enabled).apply();
    }

    public String getTelegramBotToken() {
        return prefs.getString(KEY_TELEGRAM_BOT_TOKEN, "");
    }

    public void setTelegramBotToken(String token) {
        prefs.edit().putString(KEY_TELEGRAM_BOT_TOKEN, token == null ? "" : token.trim()).apply();
    }

    public String getTelegramBaseUrl() {
        return prefs.getString(KEY_TELEGRAM_BASE_URL, "https://api.telegram.org");
    }

    public void setTelegramBaseUrl(String url) {
        prefs.edit().putString(KEY_TELEGRAM_BASE_URL,
                url == null ? "https://api.telegram.org" : url.trim()).apply();
    }

    public void applyTheme() {
        applyTheme(getTheme());
    }

    public static void applyTheme(Theme theme) {
        switch (theme) {
            case LIGHT:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
                break;
            case DARK:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
                break;
            default:
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
                } else {
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_AUTO_BATTERY);
                }
                break;
        }
    }
}
