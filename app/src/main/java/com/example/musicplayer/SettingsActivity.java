package com.example.musicplayer;

import android.content.pm.PackageManager;
import android.view.View;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.SwitchCompat;

public class SettingsActivity extends BaseActivity {

    private SettingsManager settingsManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        settingsManager = new SettingsManager(this);

        // Apply status bar inset to the root scroll view
        applyStatusBarInset(findViewById(android.R.id.content));

        // Back button
        ImageView backBtn = findViewById(R.id.settingsBackBtn);
        if (backBtn != null) backBtn.setOnClickListener(v -> finish());

        RadioGroup themeGroup = findViewById(R.id.themeGroup);
        RadioGroup sortGroup = findViewById(R.id.sortGroup);
        RadioGroup accentColorGroup = findViewById(R.id.accentColorGroup);
        TextView versionText = findViewById(R.id.versionText);

        // Theme selection
        SettingsManager.Theme currentTheme = settingsManager.getTheme();
        int themeId = getThemeRadioId(currentTheme);
        if (themeId != 0) {
            RadioButton btn = findViewById(themeId);
            if (btn != null) btn.setChecked(true);
        }
        themeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            SettingsManager.Theme theme = mapThemeRadioId(checkedId);
            settingsManager.setTheme(theme);
            SettingsManager.applyTheme(theme);
            recreate();
        });

        // Tracks sort selection
        SettingsManager.TracksSort currentSort = settingsManager.getTracksSort();
        int sortId = getSortRadioId(currentSort);
        if (sortId != 0) {
            RadioButton btn = findViewById(sortId);
            if (btn != null) btn.setChecked(true);
        }
        sortGroup.setOnCheckedChangeListener((group, checkedId) -> {
            SettingsManager.TracksSort sort = mapSortRadioId(checkedId);
            settingsManager.setTracksSort(sort);
        });

        // Accent color selection
        SettingsManager.AccentColor currentAccent = settingsManager.getAccentColor();
        int accentId = getAccentRadioId(currentAccent);
        if (accentId != 0) {
            RadioButton btn = findViewById(accentId);
            if (btn != null) btn.setChecked(true);
        }
        accentColorGroup.setOnCheckedChangeListener((group, checkedId) -> {
            SettingsManager.AccentColor accent = mapAccentRadioId(checkedId);
            settingsManager.setAccentColor(accent);
            recreate();
        });

        versionText.setText(getString(R.string.settings_version, getVersionName()));

        // AudD API key
        EditText auddInput = findViewById(R.id.auddApiKeyInput);
        String savedKey = settingsManager.getAuddApiKey();
        if (!savedKey.isEmpty()) auddInput.setText(savedKey);
        auddInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) {
                settingsManager.setAuddApiKey(s.toString().trim());
            }
        });

        // Pause on call / audio focus toggle
        SwitchCompat pauseOnFocusSwitch = findViewById(R.id.pauseOnFocusSwitch);
        if (pauseOnFocusSwitch != null) {
            pauseOnFocusSwitch.setChecked(settingsManager.shouldPauseOnFocusLoss());
            pauseOnFocusSwitch.setOnCheckedChangeListener((button, isChecked) ->
                    settingsManager.setPauseOnFocusLoss(isChecked));
        }

        // Pause on headphone disconnect toggle
        SwitchCompat pauseOnNoisySwitch = findViewById(R.id.pauseOnNoisySwitch);
        if (pauseOnNoisySwitch != null) {
            pauseOnNoisySwitch.setChecked(settingsManager.shouldPauseOnBecomingNoisy());
            pauseOnNoisySwitch.setOnCheckedChangeListener((button, isChecked) ->
                    settingsManager.setPauseOnBecomingNoisy(isChecked));
        }


    }

    private String getVersionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }

    private int getThemeRadioId(@NonNull SettingsManager.Theme theme) {
        switch (theme) {
            case LIGHT: return R.id.themeLight;
            case DARK: return R.id.themeDark;
            default: return R.id.themeSystem;
        }
    }

    private SettingsManager.Theme mapThemeRadioId(int id) {
        if (id == R.id.themeLight) return SettingsManager.Theme.LIGHT;
        if (id == R.id.themeDark) return SettingsManager.Theme.DARK;
        return SettingsManager.Theme.SYSTEM;
    }

    private int getSortRadioId(@NonNull SettingsManager.TracksSort sort) {
        switch (sort) {
            case ARTIST: return R.id.sortArtist;
            case ALBUM: return R.id.sortAlbum;
            case DURATION: return R.id.sortDuration;
            case DATE_ADDED: return R.id.sortDateAdded;
            default: return R.id.sortTitle;
        }
    }

    private SettingsManager.TracksSort mapSortRadioId(int id) {
        if (id == R.id.sortArtist) return SettingsManager.TracksSort.ARTIST;
        if (id == R.id.sortAlbum) return SettingsManager.TracksSort.ALBUM;
        if (id == R.id.sortDuration) return SettingsManager.TracksSort.DURATION;
        if (id == R.id.sortDateAdded) return SettingsManager.TracksSort.DATE_ADDED;
        return SettingsManager.TracksSort.TITLE;
    }

    private int getAccentRadioId(@NonNull SettingsManager.AccentColor accent) {
        switch (accent) {
            case BLUE: return R.id.accentBlue;
            case ORANGE: return R.id.accentOrange;
            case PURPLE: return R.id.accentPurple;
            default: return R.id.accentGreen;
        }
    }

    private SettingsManager.AccentColor mapAccentRadioId(int id) {
        if (id == R.id.accentBlue) return SettingsManager.AccentColor.BLUE;
        if (id == R.id.accentOrange) return SettingsManager.AccentColor.ORANGE;
        if (id == R.id.accentPurple) return SettingsManager.AccentColor.PURPLE;
        return SettingsManager.AccentColor.GREEN;
    }
}
