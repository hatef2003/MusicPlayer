package com.example.musicplayer;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

public abstract class BaseActivity extends AppCompatActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        SettingsManager settingsManager = new SettingsManager(this);
        settingsManager.applyTheme();
        setTheme(settingsManager.getAccentColor().getThemeResId());
        super.onCreate(savedInstanceState);

        // Enable edge-to-edge: let content draw behind system bars
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
    }

    /**
     * Apply system bar insets as top padding to the given view.
     * Call this after setContentView() in each activity with its root/header view.
     */
    protected void applyStatusBarInset(View view) {
        ViewCompat.setOnApplyWindowInsetsListener(view, (v, insets) -> {
            int statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
            v.setPadding(v.getPaddingLeft(), statusBarHeight,
                    v.getPaddingRight(), v.getPaddingBottom());
            return insets;
        });
    }

    /**
     * Handle the API 30+ MediaStore delete result from SongActionsHelper.
     * Override in subclasses but call super.onActivityResult() first.
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        SongActionsHelper.handleDeleteResult(requestCode, resultCode);
    }

    /**
     * Apply navigation bar insets as bottom padding to the given view.
     * Use this for views at the bottom of the screen that must stay above the nav bar.
     */
    protected void applyNavigationBarInset(View view) {
        ViewCompat.setOnApplyWindowInsetsListener(view, (v, insets) -> {
            int navBarHeight = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(),
                    v.getPaddingRight(), navBarHeight);
            return insets;
        });
    }
}
