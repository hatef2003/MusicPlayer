package com.example.musicplayer;

import android.app.Application;

public class MusicPlayerApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        SettingsManager settingsManager = new SettingsManager(this);
        settingsManager.applyTheme();
        setTheme(settingsManager.getAccentColor().getThemeResId());
    }
}
