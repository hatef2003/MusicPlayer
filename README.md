# 🎵 Music Player — Android

A full-featured, hacker-themed Android music player built in **Java** with a terminal-green aesthetic. Scans your device for music, lists tracks and albums with sorting/search, plays audio with a foreground service and media notification, fetches and caches lyrics from the internet, embeds lyrics into audio files, and supports theme customization with four accent colors.

![Platform](https://img.shields.io/badge/Platform-Android-3DDC84?logo=android)
![Language](https://img.shields.io/badge/Language-Java-ED8B00?logo=openjdk)
![Min SDK](https://img.shields.io/badge/Min%20SDK-24-3DDC84)
![Target SDK](https://img.shields.io/badge/Target%20SDK-34-3DDC84)

---

## Table of Contents

- [Features](#features)
- [Screenshots / Screen Overview](#screenshots--screen-overview)
- [Architecture](#architecture)
- [Project Structure](#project-structure)
- [Tech Stack & Dependencies](#tech-stack--dependencies)
- [Permissions](#permissions)
- [Key Classes](#key-classes)
- [Detailed Feature Breakdown](#detailed-feature-breakdown)
  - [Music Discovery](#music-discovery)
  - [Playback Architecture](#playback-architecture)
  - [Now Playing Screen](#now-playing-screen)
  - [Mini Player](#mini-player)
  - [Lyrics System](#lyrics-system)
  - [Lyrics Embedding (Jaudiotagger)](#lyrics-embedding-jaudiotagger)
  - [Search & Filtering](#search--filtering)
  - [Sorting](#sorting)
  - [Album Browser](#album-browser)
  - [Album Detail View](#album-detail-view)
  - [Audio Output Selection](#audio-output-selection)
  - [Lock Screen & Notification Controls](#lock-screen--notification-controls)
  - [Settings & Theming](#settings--theming)
  - [Speech-to-Text Fallback](#speech-to-text-fallback)
- [UI Design Philosophy](#ui-design-philosophy)
- [Build & Run](#build--run)
- [Known Notes](#known-notes)
- [Future Improvements](#future-improvements)
- [License](#license)

---

## Features

| Feature | Description |
|---|---|
| **Tracks Tab** | Scrollable list of all songs on the device with title, artist, track number, and duration |
| **Albums Tab** | 2-column grid of albums with cover art, artist name, and track count |
| **Album Detail** | Tap an album to view its songs and play from the album |
| **Search** | Real-time search across tracks and albums via SearchView in the toolbar |
| **Sorting** | Sort tracks by Title, Artist, Album, Duration, or Date Added (via dialog or Settings) |
| **Playback** | Foreground `MediaPlayer` service with play queue, auto-advance, and media notification |
| **Now Playing** | Full-screen player with album art, seek bar, time labels, transport controls, and MediaRouteButton |
| **Mini Player** | Persistent bar at the bottom of the main screen with current song info, seek bar, and play/pause |
| **Lyrics (Online)** | Fetches synced (LRC) and plain lyrics from lrclib.net API |
| **Lyrics Cache** | File-based JSON cache in `cacheDir/lyrics/` — lyrics persist across sessions and work offline |
| **Lyrics Embedding** | Embeds fetched lyrics into audio file metadata (ID3 USLT / Vorbis LYRICS) using Jaudiotagger |
| **Lyrics Refresh** | Tap "🔄 Refresh" to re-fetch lyrics from the internet, skipping cache |
| **Lyrics Toggle** | Tap album art to toggle lyrics mode — art shrinks and lyrics scroll area appears |
| **Speech-to-Text** | Fallback: tap 🎤 to dictate lyrics via Android speech recognition |
| **Theme Selection** | System / Light / Dark mode with `AppCompatDelegate` |
| **Accent Colors** | Green (default), Blue, Orange, Purple — applied via Material theme variants |
| **Album Cover Art** | Loaded from MediaStore via Glide — shown in album grid, album detail, now playing, and notification |
| **Audio Output** | `MediaRouteButton` on Now Playing screen to route audio to Bluetooth, speaker, Cast, etc. |
| **Lock Screen Controls** | `MediaSessionCompat` integration for lock screen / notification media controls |
| **Night Mode** | Full dark theme with `values-night/` resource overrides |

---

## Screenshots / Screen Overview

```
┌─────────────────────────────────┐
│  MainActivity                   │
│  ┌───────────────────────┐ [⚙] │  ← SearchView + Settings
│  │ Search tracks & albums│      │
│  └───────────────────────┘      │
│  ┌──────┬──────┐                │
│  │Tracks│Albums│  (TabLayout)   │
│  └──────┴──────┘                │
│                                 │
│  ViewPager2 content:            │
│  ┌───────────────────────────┐  │
│  │ 01  Song Title            │  │
│  │     Artist Name     3:45  │  │
│  ├───────────────────────────┤  │
│  │ 02  Song Title            │  │
│  │     Artist Name     4:12  │  │
│  └───────────────────────────┘  │
│                                 │
│  ┌───────────────────────────┐  │
│  │ ┃ Mini Player  [▶] [═══] │  │  ← appears when playing
│  └───────────────────────────┘  │
└─────────────────────────────────┘

         ┌─────────────────────────────┐
         │  NowPlayingActivity         │
         │                    [🔊]     │  ← MediaRouteButton
         │   ┌───────────────────┐     │
         │   │   Album Cover Art │     │  ← tap to toggle lyrics
         │   │                   │     │
         │   └───────────────────┘     │
         │     Song Title              │
         │     Artist Name             │
         │     Album Name              │
         │   ═══════════════════       │  ← SeekBar
         │   0:00             3:45     │
         │                             │
         │   ┌─────────────────────┐   │
         │   │ Lyrics        🔄   │   │  ← header with refresh
         │   │ Line 1 of lyrics   │   │
         │   │ Line 2 of lyrics   │   │  ← ScrollView
         │   │ Line 3 of lyrics   │   │
         │   └─────────────────────┘   │
         │                             │
         │   ⏮      ▶/⏸      ⏭      │  ← transport controls
         └─────────────────────────────┘
```

---

## Architecture

```
┌─────────────────────────────────────────────────────┐
│                    UI Layer                          │
│  MainActivity ←→ TracksFragment / AlbumsFragment     │
│  AlbumDetailActivity                                 │
│  NowPlayingActivity                                  │
│  SettingsActivity                                    │
│         ↕ PlaybackManager.Callback                   │
├─────────────────────────────────────────────────────┤
│               Business Logic                         │
│  PlaybackManager (singleton)                         │
│  MusicRepository (static MediaStore queries)         │
│  SettingsManager (SharedPreferences wrapper)         │
│  LyricsService (fetch + parse + cache + embed)       │
│  LyricsCache (file-based JSON)                       │
│  LyricsEmbedder (Jaudiotagger ID3/Vorbis)            │
│         ↕ ServiceConnection (bind)                   │
├─────────────────────────────────────────────────────┤
│               Service Layer                          │
│  MusicService (foreground Service)                   │
│  MediaPlayer ← MediaSessionCompat                   │
│  NotificationCompat + MediaStyle                    │
├─────────────────────────────────────────────────────┤
│               Data Layer                             │
│  Song / Album models                                 │
│  MediaStore ContentProvider                          │
│  lrclib.net REST API                                 │
│  File-based lyrics cache (cacheDir/lyrics/)          │
└─────────────────────────────────────────────────────┘
```

The architecture follows a clean separation:

- **Activities/Fragments** handle UI rendering and user interaction
- **PlaybackManager** is a singleton that bridges UI to the service, managing `ServiceConnection` and dispatching callbacks
- **MusicService** is a bound + started foreground service wrapping `MediaPlayer`, managing play queue, notifications, and `MediaSessionCompat`
- **MusicRepository** provides static methods to query `MediaStore` for songs, albums, and album art URIs
- **LyricsService** orchestrates the lyrics pipeline: cache → API → embedded metadata → speech-to-text
- **SettingsManager** wraps `SharedPreferences` for theme, sort, and accent color persistence

---

## Project Structure

```
androidMusicPlayer/
├── build.gradle                              # Root build file (AGP 8.5.2)
├── settings.gradle                           # Module declaration
├── gradle.properties                         # JVM args + AndroidX flags
├── README.md                                 # This file
│
└── app/
    ├── build.gradle                          # App module config + dependencies
    ├── proguard-rules.pro
    │
    └── src/main/
        ├── AndroidManifest.xml               # Permissions, activities, service, attribution
        │
        ├── java/com/example/musicplayer/
        │   ├── MainActivity.java             # Tab host + mini player + permission + search
        │   ├── BaseActivity.java             # Theme/accent application base class
        │   ├── MusicPlayerApplication.java   # Application class — applies theme on startup
        │   ├── TracksFragment.java           # RecyclerView list of all songs + sorting + search
        │   ├── AlbumsFragment.java           # Grid RecyclerView of albums + search
        │   ├── AlbumDetailActivity.java      # Album header + song list for a selected album
        │   ├── NowPlayingActivity.java       # Full-screen player + lyrics + speech + audio output
        │   ├── MusicService.java             # Foreground MediaPlayer service + notification
        │   ├── PlaybackManager.java          # Singleton binding UI to MusicService
        │   ├── MusicRepository.java          # MediaStore queries (songs + albums + art URIs)
        │   ├── LyricsService.java            # Lyrics fetching (API + cache + embedded)
        │   ├── LyricsCache.java              # File-based JSON lyrics cache
        │   ├── LyricsEmbedder.java           # Jaudiotagger lyrics embedding (ID3/Vorbis)
        │   ├── LyricLine.java                # Lyric line model (timestamp + text)
        │   ├── SettingsActivity.java         # Theme / sort / accent color settings
        │   ├── SettingsManager.java          # SharedPreferences wrapper for all settings
        │   ├── SongAdapter.java              # RecyclerView adapter for song rows
        │   ├── AlbumAdapter.java             # RecyclerView adapter for album cards (programmatic)
        │   └── model/
        │       ├── Song.java                 # Song data model (immutable)
        │       └── Album.java                # Album data model (mutable song list)
        │
        └── res/
            ├── layout/
            │   ├── activity_main.xml             # TabLayout + SearchView + ViewPager2 + mini player
            │   ├── activity_now_playing_v2.xml   # Album art + seek + lyrics + controls + MediaRoute
            │   ├── activity_album_detail.xml     # Album header + song list
            │   ├── activity_settings.xml         # Theme / sort / accent radio groups
            │   ├── fragment_list.xml             # Shared RecyclerView + empty state + sort label
            │   ├── item_song.xml                 # Song row layout
            │   └── item_album_card.xml           # Album card layout (fallback)
            │
            ├── drawable/
            │   ├── bg_terminal_item.xml           # Terminal-style item background
            │   ├── ic_album.xml                   # Album art placeholder (vector)
            │   ├── ic_launcher_foreground.xml     # Launcher icon foreground
            │   ├── ic_next.xml                    # Next track icon
            │   ├── ic_pause.xml                   # Pause icon
            │   ├── ic_play.xml                    # Play icon
            │   ├── ic_previous.xml                # Previous track icon
            │   └── ic_settings.xml                # Settings gear icon
            │
            ├── mipmap-anydpi-v26/
            │   ├── ic_launcher.xml                # Adaptive launcher icon (API 26+)
            │   └── ic_launcher_round.xml
            │
            ├── mipmap/
            │   ├── ic_launcher.xml                # Fallback launcher icon (API 24-25)
            │   └── ic_launcher_round.xml
            │
            ├── values/
            │   ├── colors.xml                     # Hacker terminal color palette
            │   ├── strings.xml                    # App strings
            │   ├── themes.xml                     # Material theme + 4 accent variants
            │   └── ids.xml                        # Pre-declared IDs (MediaRouteButton fix)
            │
            └── values-night/
                └── (night mode color overrides)
```

---

## Tech Stack & Dependencies

### Build Configuration

| Component | Version |
|---|---|
| Android Gradle Plugin | `8.5.2` |
| Gradle Wrapper | `8.9` |
| compileSdk | `34` (Android 14) |
| minSdk | `24` (Android 7.0 Nougat) |
| targetSdk | `34` |
| Java | `17` |
| Namespace | `com.example.musicplayer` |

### Libraries

| Library | Version | Purpose |
|---|---|---|
| `androidx.appcompat:appcompat` | `1.7.0` | Backward-compatible Activity/UI |
| `com.google.android.material:material` | `1.12.0` | Material Design (TabLayout, CardView, SearchView) |
| `androidx.constraintlayout:constraintlayout` | `2.1.4` | Layout positioning |
| `androidx.recyclerview:recyclerview` | `1.3.2` | Song/album lists |
| `androidx.viewpager2:viewpager2` | `1.1.0` | Swipeable Tracks/Albums tabs |
| `androidx.media:media` | `1.7.0` | MediaSession + MediaStyle notification |
| `androidx.mediarouter:mediarouter` | `1.7.0` | Audio output device selection (MediaRouteButton) |
| `com.github.bumptech.glide:glide` | `4.16.0` | Album cover art image loading (async, cached) |
| `net.jthink:jaudiotagger` | `3.0.1` | Audio metadata read/write (ID3, Vorbis LYRICS tag) |

### Network

| Service | Purpose |
|---|---|
| [lrclib.net](https://lrclib.net) | Free, open-source lyrics API — returns synced LRC and plain text lyrics |

---

## Permissions

| Permission | Reason | API Level |
|---|---|---|
| `READ_MEDIA_AUDIO` | Access music files on device | API 33+ |
| `READ_EXTERNAL_STORAGE` | Access music files (legacy) | API ≤ 32 |
| `FOREGROUND_SERVICE` | Run playback service in foreground | All |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Media playback foreground service type | API 34+ |
| `POST_NOTIFICATIONS` | Show playback notification | API 33+ |
| `INTERNET` | Fetch lyrics from lrclib.net API | All |

Runtime permission handling uses `ActivityResultContracts.RequestMultiplePermissions` with graceful degradation.

---

## Key Classes

| Class | Responsibility |
|---|---|
| `MainActivity` | Entry point. Hosts TabLayout + ViewPager2 (Tracks/Albums). Manages runtime permissions, SearchView, settings button, and the mini player bar. |
| `BaseActivity` | Abstract base class. Applies theme (System/Light/Dark) and accent color (Green/Blue/Orange/Purple) in `onCreate()` before `super.onCreate()`. |
| `MusicPlayerApplication` | `Application` subclass. Applies saved theme on cold start so the splash/launch screen uses correct colors. |
| `TracksFragment` | RecyclerView listing all songs from MediaStore. Supports sorting (Title/Artist/Album/Duration/Date Added) via dialog and search filtering. Tap to play. |
| `AlbumsFragment` | Grid RecyclerView (2 columns) of albums with cover art. Search filterable. Tap to open AlbumDetailActivity. |
| `AlbumDetailActivity` | Shows album header (art + name + artist) and the album's song list. Plays songs from the album on tap. |
| `NowPlayingActivity` | Full-screen player with album art (tap to toggle lyrics), seek bar, time labels, prev/play-pause/next, MediaRouteButton, lyrics display (ScrollView), refresh button, fetch button, and speech-to-text button. |
| `MusicService` | Bound + started foreground service wrapping `MediaPlayer`. Manages play queue, play/pause/next/prev/seek, `MediaSessionCompat` metadata, `MediaStyle` notification with album art, and auto-advance on song completion. |
| `PlaybackManager` | Singleton bridge between UI and MusicService. Manages `ServiceConnection`, exposes callback API (`onPlaybackChanged`), and delegates play/pause/next/prev/seek calls. |
| `MusicRepository` | Static methods to query `MediaStore.Audio.Media.EXTERNAL_CONTENT_URI` for songs and albums. Builds album art content URIs via `ContentUris.withAppendedId()`. |
| `LyricsService` | Orchestrates lyrics pipeline: (1) check `LyricsCache`, (2) fetch from lrclib.net API, (3) extract embedded lyrics via `MediaMetadataRetriever`, (4) embed fetched lyrics into audio file. Parses both LRC (synced) and plain text formats. |
| `LyricsCache` | File-based JSON cache in `cacheDir/lyrics/`. Keyed by sanitized `artist_title.json`. Stores `LyricLine` array with timestamps. Corrupted files auto-delete. |
| `LyricsEmbedder` | Embeds lyrics into audio files using Jaudiotagger. Supports direct file access (API < 29) and scoped storage (API 29+) via copy-modify-write-back. Handles ID3 USLT for MP3 and Vorbis LYRICS for FLAC. |
| `LyricLine` | Data model: `timestampMs` (-1 for plain) + `text`. `isSynced()` returns true if timestamp ≥ 0. |
| `SettingsManager` | `SharedPreferences` wrapper for Theme (System/Light/Dark), TracksSort (Title/Artist/Album/Duration/DateAdded), and AccentColor (Green/Blue/Orange/Purple). |
| `SettingsActivity` | Settings screen with radio groups for theme, default sort order, and accent color. Shows app version. |
| `SongAdapter` | RecyclerView adapter for song rows. Shows track number, title, artist, and duration. |
| `AlbumAdapter` | RecyclerView adapter for album cards. **Built programmatically** (no XML inflation) to avoid GridLayoutManager layout issues. Shows album art, name, artist, and track count. |
| `Song` | Immutable data model: id, title, artist, album, albumId, duration, dateAdded, contentUri. |
| `Album` | Data model: id, name, artist, mutable song list. Helper methods for songCount and totalDuration. |

---

## Detailed Feature Breakdown

### Music Discovery

`MusicRepository.loadAllSongs()` queries `MediaStore.Audio.Media.EXTERNAL_CONTENT_URI` with `IS_MUSIC != 0`. Columns fetched:

- `_ID`, `TITLE`, `ARTIST`, `ALBUM`, `ALBUM_ID`, `DURATION`, `DATE_ADDED`

Content URIs are built via `ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)`.

`MusicRepository.loadAlbums()` groups songs by `ALBUM_ID` using a `LinkedHashMap` (preserving insertion order). The first artist seen for each album wins.

Album art URIs are built via:
```java
ContentUris.withAppendedId(
    Uri.parse("content://media/external/audio/albumart"), albumId)
```

### Playback Architecture

```
UI (Activity/Fragment)
       ↕ PlaybackManager.Callback
PlaybackManager (singleton)
       ↕ ServiceConnection (bind)
MusicService (foreground Service)
       ↕
MediaPlayer (Android framework)
```

**MusicService** lifecycle:
1. Created when first activity binds
2. Starts as foreground service with `MediaStyle` notification
3. Manages a play queue (`List<Song>`) and `currentIndex`
4. `setQueue(songs, startIndex)` → `playCurrent()` → `MediaPlayer.prepare()` + `start()`
5. Auto-advances to next song on `onCompletion()`
6. Stops foreground and notifies UI when queue ends
7. Handles notification actions: PAUSE, RESUME, NEXT, PREV, STOP

**MediaSessionCompat** provides:
- Title, artist, album, duration, album art bitmap
- Lock screen controls
- Notification media style integration

**Album art in notification**:
- Loaded on a background `ExecutorService` via `Glide.asBitmap().submit(256, 256).get()`
- Set as `setLargeIcon()` on notification and `METADATA_KEY_ALBUM_ART` in `MediaMetadataCompat`
- Notification refreshed on main thread via `Handler` after bitmap loads

### Now Playing Screen

- **Album art**: Tappable — toggles lyrics mode (shrinks from 240dp to 100dp)
- **Title / Artist / Album**: Monospace green text
- **SeekBar**: Synced every 500ms via `Handler`. `userDragging` flag prevents conflicts.
- **Time labels**: `current time` / `total time` in `m:ss` format
- **Transport controls**: Previous (56dp), Play/Pause (72dp), Next (56dp) with ripple effects
- **MediaRouteButton**: Configured with `CATEGORY_LIVE_AUDIO` and `CATEGORY_LIVE_VIDEO`
- **Lyrics section**: ScrollView with plain text lyrics, refresh button, fetch button
- **No-lyrics fallback**: Shows "Fetch lyrics from internet" and "Tap to speak lyrics" buttons

### Mini Player

- Appears at bottom of `MainActivity` when a song is playing
- Shows: green accent bar | title + artist | seek bar | play/pause button
- Seek bar synced every 500ms with `userDragging` protection
- Tapping the mini player opens `NowPlayingActivity`
- Hidden (`GONE`) when no song is active

### Lyrics System

**Pipeline** (`LyricsService.fetchLyrics()`):
1. **Cache check**: `LyricsCache.get(artist, title)` → return if found
2. **API fetch**: `GET https://lrclib.net/api/get?artist_name=...&track_name=...`
   - Prefers `syncedLyrics` (LRC format with timestamps)
   - Falls back to `plainLyrics`
3. **Embedded metadata**: `MediaMetadataRetriever.extractMetadata(19)` (or key 20)
4. **Not found**: Callback with error

**LRC Parsing** (`parseLRC()`):
- Regex: `\[(\d{2}):(\d{2})\.(\d{2,3})\]`
- Supports multiple timestamps per line
- Converts to `LyricLine(timestampMs, text)` objects
- Lines sorted by timestamp

**Plain text parsing** (`parseLyrics()`):
- Splits on newlines, creates `LyricLine(-1, text)` for each non-empty line

**Display**:
- Lyrics shown as plain text in a `ScrollView` + `TextView` (monospace, green)
- Tap album art to toggle lyrics mode (art shrinks, lyrics appear)
- "🔄 Refresh" button re-fetches from internet (skips cache)
- "🌐 Fetch lyrics from internet" button when no lyrics available

### Lyrics Embedding (Jaudiotagger)

After successful API fetch, lyrics are embedded into the audio file's metadata:

**`LyricsEmbedder.embedLyrics()`**:
1. **Direct file path** (API < 29): `AudioFileIO.read(file)` → `tag.setField(FieldKey.LYRICS, lyrics)` → `AudioFileIO.write(af)`
2. **Scoped storage** (API 29+): Copy audio to temp file → modify with Jaudiotagger → write back via `contentResolver.openOutputStream(uri, "wt")`

**Embedding runs on a single daemon thread** (`ExecutorService`) to avoid thread explosion.

**Supported formats**: MP3 (ID3v2 USLT), FLAC (Vorbis LYRICS), and any format Jaudiotagger supports.

### Search & Filtering

- `SearchView` in the toolbar with real-time query text listener
- Queries forwarded to both `TracksFragment` and `AlbumsFragment`
- **Tracks**: Filters by title OR artist (case-insensitive, `Locale.ROOT`)
- **Albums**: Filters by album name OR artist
- Empty state: "No matches found" when search yields no results
- Search query persisted across configuration changes via `onSaveInstanceState()`

### Sorting

Five sort modes for the Tracks tab:

| Sort | Comparator |
|---|---|
| Title | Alphabetical by title (case-insensitive) |
| Artist | Alphabetical by artist, then by title |
| Album | Alphabetical by album, then by title |
| Duration | Shortest to longest, then by title |
| Date Added | Newest first (reversed), then by title |

Sort is accessible via:
- **Sort label** in the fragment (tappable, shows current sort)
- **AlertDialog** with single-choice items
- **Settings screen** (persists across sessions)

### Album Browser

- 2-column `GridLayoutManager`
- Each card: album art (120dp), album name (bold monospace), artist, track count
- **Built programmatically** in `AlbumAdapter.createAlbumCardView()` to bypass XML inflation issues with `GridLayoutManager`
- Glide loads album art with `ic_album` placeholder

### Album Detail View

- Album art (loaded via Glide), album name, artist name
- Song list below in a `LinearLayoutManager` RecyclerView
- Plays songs from the album on tap
- Parent activity: `MainActivity` (enables Up navigation)

### Audio Output Selection

`MediaRouteButton` on the Now Playing screen:
- Configured with `MediaRouteSelector` supporting `CATEGORY_LIVE_AUDIO` and `CATEGORY_LIVE_VIDEO`
- Opens system audio output picker
- Supports Bluetooth, speakers, Cast devices, etc.

### Lock Screen & Notification Controls

- `MediaSessionCompat` provides metadata to the system
- `MediaStyle` notification with:
  - Small icon (play icon)
  - Large icon (album art bitmap, loaded async)
  - Title + artist
  - Previous / Play-Pause / Next actions (compact view: all 3)
  - Clicking notification opens `NowPlayingActivity`
- Foreground service type: `mediaPlayback` (required for API 34+)

### Settings & Theming

**Theme Mode** (System / Light / Dark):
- Uses `AppCompatDelegate.setDefaultNightMode()`
- System mode: `MODE_NIGHT_FOLLOW_SYSTEM` (API 29+) or `MODE_NIGHT_AUTO_BATTERY`
- Applied in `BaseActivity.onCreate()` and `MusicPlayerApplication.onCreate()`

**Accent Color** (Green / Blue / Orange / Purple):
- Each has a dedicated theme variant (`Theme.MusicPlayer.Blue`, etc.)
- Overrides `colorSecondary`, `colorSecondaryVariant`, `colorControlActivated`, `colorControlHighlight`
- Applied via `setTheme()` in `BaseActivity.onCreate()` before `super.onCreate()`

**Default Sort** (Title / Artist / Album / Duration / Date Added):
- Persisted in `SharedPreferences`
- Applied when TracksFragment resumes

**Color Palette** (Hacker Terminal Theme):
- Background: `#000000` (pure black)
- Surface: `#0A0A0A` (near-black)
- Primary: `#0A0A0A`
- Text Primary: `#00FF41` (terminal green)
- Text Secondary: `#00B32C` (dimmed green)
- Accent Green: `#00FF41`
- Accent Blue: `#00A8FF`
- Accent Orange: `#FF9D00`
- Accent Purple: `#B829DD`

### Speech-to-Text Fallback

When no lyrics are found:
- User can tap "🎤 Tap to speak lyrics"
- Launches `RecognizerIntent.ACTION_RECOGNIZE_SPEECH`
- Result displayed as lyrics text with label "Lyrics (speech-to-text)"
- Gracefully handles `ActivityNotFoundException` if speech recognition unavailable

---

## UI Design Philosophy

The app uses a **hacker terminal aesthetic**:

- **Monospace font** (`android:fontFamily="monospace"`) throughout
- **Terminal green** (`#00FF41`) as primary text color on pure black background
- **Dimmed green** (`#00B32C`) for secondary text
- **Dark surfaces** (`#0A0A0A`) with subtle elevation
- **Minimal chrome** — content-focused with functional UI elements
- **Programmatic album cards** — built in Java to avoid XML inflation crashes with `GridLayoutManager`
- **Night mode** — full dark theme via `values-night/` resource qualifiers

---

## Build & Run

### Prerequisites

- **Android Studio** (Hedgehog 2023.1.1 or newer recommended)
- **JDK 17** (bundled with recent Android Studio versions)
- An **Android device or emulator** running API 24+ with music files on it

### Steps

1. **Open the project**
   - Launch Android Studio → `File` → `Open` → select the project root directory

2. **Let Gradle sync**
   - Android Studio will automatically download Gradle 8.9 and all dependencies

3. **Run the app**
   - Connect your device or start an emulator
   - Click the **Run** button (▶) or press `Shift+F10`

4. **Grant permissions**
   - On first launch, the app requests audio access (and notification permission on API 33+)
   - Tap **Allow** — the track and album lists will populate

### Command Line

```bash
# Debug build
./gradlew assembleDebug

# Install on connected device
./gradlew installDebug

# Clean build
./gradlew clean assembleDebug
```

---

## Known Notes

- **`attributionTag` warning**: Fixed by declaring `<attribution android:tag="audioPlayback" ...>` in the manifest.
- **MediaRouteButton ID**: Pre-declared in `ids.xml` and referenced via `@id/` (not `@+id/`) to work around an AAPT2 conflict.
- **`setAudioStreamType` deprecation**: `MusicService` uses `STREAM_MUSIC` via the deprecated method for simplicity. Production apps should migrate to `setAudioAttributes()` with `AudioAttributes.USAGE_MEDIA`.
- **Album cards are programmatic**: `AlbumAdapter` builds views in Java code instead of inflating XML, to avoid `GridLayoutManager` layout parameter issues that caused crashes.
- **Scoped storage**: `LyricsEmbedder` falls back to copy-modify-write-back on API 29+ where direct file paths are unavailable.
- **No Gradle wrapper JAR**: The project includes `gradle-wrapper.properties` but not the JAR binary. Android Studio generates it on first sync.

---

## Future Improvements

- Equalizer / audio effects
- Playlist creation and management
- Spotify/Apple Music-style swipe gestures
- Offline lyrics database
- Widget support (home screen player)
- Android Auto integration
- Sleep timer
- Replay gain normalization
- Folder-based browsing
- Last.fm scrobbling

---

## License

This project is provided as-is for educational and demo purposes. Feel free to use and modify it freely.
