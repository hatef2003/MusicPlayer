package com.example.musicplayer;

import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

public abstract class BaseActivity extends AppCompatActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        SettingsManager settingsManager = new SettingsManager(this);
        settingsManager.applyTheme();
        setTheme(settingsManager.getAccentColor().getThemeResId());
        super.onCreate(savedInstanceState);
    }
}
