# Music Player

A modern Android music player with a clean, dark interface built in Java.

## Features

- **Browse your library** — Tracks, Albums, and Playlists tabs with search
- **Now Playing** — Full-screen player with album art, progress bar, and playback controls
- **Synced Lyrics** — Fetches and displays time-synced lyrics from AUDD API
- **Lyrics Embedding** — Embed lyrics directly into audio file metadata
- **Playlist Management** — Create, edit, and manage custom playlists with M3U support
- **Album Detail View** — Browse songs by album with album art
- **System Media Controls** — Native Android MediaStyle notification with playback controls on lock screen and quick settings
- **Themes** — System, Light, and Dark mode support
- **Accent Colors** — Green, Blue, Orange, and Purple themes
- **Sorting** — Sort by title, artist, album, duration, or date added
- **Headphone & Bluetooth** — Media button support via MediaSession

## Screenshots

> _Coming soon_

## Requirements

- Android 7.0 (API 24) or higher
- Local music files on device storage

## Tech Stack

| Component | Technology |
|---|---|
| Language | Java 17 |
| UI | AndroidX, Material Design, ConstraintLayout, ViewPager2, RecyclerView |
| Image Loading | Glide 4.16 |
| Audio Metadata | jaudiotagger 3.0.1 |
| Media | AndroidX Media, MediaSession, MediaRouter |
| Build | Gradle 8.5.2, AGP 8.5.2 |
| Min SDK | 24 (Android 7.0) |
| Target SDK | 34 (Android 14) |

## Permissions

| Permission | Purpose |
|---|---|
| `READ_MEDIA_AUDIO` | Access music files (Android 13+) |
| `READ_EXTERNAL_STORAGE` | Access music files (Android 12 and below) |
| `WRITE_EXTERNAL_STORAGE` | Embed lyrics into audio files (Android 10 and below) |
| `INTERNET` | Fetch lyrics from AUDD API |
| `FOREGROUND_SERVICE` | Background music playback |
| `POST_NOTIFICATIONS` | Media playback notification (Android 13+) |

## Build

```bash
# Clone the repository
git clone https://github.com/hatef2003/MusicPlayer.git
cd MusicPlayer

# Build debug APK
./gradlew assembleDebug

# Install on connected device
./gradlew installDebug
```

## License

**All Rights Reserved.**

Copyright (c) 2026 Hatef Rezaee. All rights reserved.

This software is proprietary. No permission is granted to use, copy, modify, or distribute this software without express prior written permission of the copyright holder.

See [LICENSE](LICENSE) for details.

## Author

**Hatef Rezaee**
