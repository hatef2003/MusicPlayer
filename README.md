# 🎵 Music Player

A simple, lightweight Android music player app built in **Java** that scans your device for music, lists tracks and albums, and plays them with a clean Material Design UI.

![Platform](https://img.shields.io/badge/Platform-Android-3DDC84?logo=android)
![Language](https://img.shields.io/badge/Language-Java-ED8B00?logo=openjdk)
![Min SDK](https://img.shields.io/badge/Min%20SDK-24-3DDC84)
![Target SDK](https://img.shields.io/badge/Target%20SDK-34-3DDC84)

---

## ✨ Features

| Feature | Description |
|---|---|
| **Tracks Tab** | Lists all songs on the device in a scrollable list with title, artist, and duration |
| **Albums Tab** | Displays albums in a 2-column grid with album cover art and track count |
| **Album Detail** | Tap an album to see all its songs and play from the album |
| **Playback** | Foreground `MediaPlayer` service with a media notification (prev / play-pause / next) |
| **Now Playing** | Full-screen player with album art, seek bar, time labels, and transport controls |
| **Mini Player** | Persistent bar at the bottom of the main screen showing the current song + seek bar |
| **Album Cover Art** | Loaded from `MediaStore` via Glide — shown in album grid, album detail, now playing, and notification |
| **Audio Output Selection** | `MediaRouteButton` on the Now Playing screen to route audio to Bluetooth, speaker, Cast, etc. |
| **Lock Screen Controls** | `MediaSessionCompat` integration for lock screen / notification media controls |

---

## 📱 Screenshots / Screen Overview

```
┌─────────────────────────────┐
│  MainActivity               │
│  ┌──────┬──────┐            │
│  │Tracks│Albums│  (TabLayout)│
│  └──────┴──────┘            │
│                             │
│  ViewPager2 content:        │
│  ┌───────────────────────┐  │
│  │ ▶ Song Title          │  │
│  │   Artist Name   3:45  │  │
│  ├───────────────────────┤  │
│  │ ▶ Song Title          │  │
│  │   Artist Name   4:12  │  │
│  └───────────────────────┘  │
│                             │
│  ┌───────────────────────┐  │
│  │ Mini Player  [▶] [═══]│  │  ← appears when playing
│  └───────────────────────┘  │
└─────────────────────────────┘

         ┌─────────────────────────┐
         │  NowPlayingActivity     │
         │                  [🔊] ← MediaRouteButton│
         │   ┌───────────────┐     │
         │   │  Album Cover  │     │
         │   │     Art       │     │
         │   └───────────────┘     │
         │     Song Title          │
         │     Artist Name         │
         │     Album Name          │
         │   ═══════════════       │
         │   0:00           3:45   │
         │   ⏮    ▶    ⏭          │
         └─────────────────────────┘
```

---

## 🏗️ Project Structure

```
androidMusicPlayer/
├── build.gradle                          # Root build file (AGP 8.5.2)
├── settings.gradle                       # Module declaration
├── gradle.properties                     # JVM args + AndroidX flags
├── gradle/wrapper/
│   └── gradle-wrapper.properties         # Gradle 8.9
│
└── app/
    ├── build.gradle                      # App module config + dependencies
    ├── proguard-rules.pro
    │
    └── src/main/
        ├── AndroidManifest.xml           # Permissions, activities, service
        │
        ├── java/com/example/musicplayer/
        │   ├── MainActivity.java         # Tab host + mini player + permission handling
        │   ├── TracksFragment.java       # RecyclerView list of all songs
        │   ├── AlbumsFragment.java       # Grid RecyclerView of albums
        │   ├── AlbumDetailActivity.java  # Songs within a selected album
        │   ├── NowPlayingActivity.java   # Full-screen player + seek bar + output selector
        │   ├── MusicService.java         # Foreground MediaPlayer service + notification
        │   ├── PlaybackManager.java      # Singleton binding UI to MusicService
        │   ├── MusicRepository.java      # MediaStore queries (songs + albums + art URIs)
        │   ├── SongAdapter.java          # RecyclerView adapter for song rows
        │   ├── AlbumAdapter.java         # RecyclerView adapter for album cards
        │   └── model/
        │       ├── Song.java             # Song data model
        │       └── Album.java            # Album data model
        │
        └── res/
            ├── layout/
            │   ├── activity_main.xml          # TabLayout + ViewPager2 + mini player
            │   ├── activity_now_playing.xml   # Album art + seek bar + controls + MediaRouteButton
            │   ├── activity_album_detail.xml  # Album header + song list
            │   ├── fragment_list.xml          # Shared RecyclerView + empty state
            │   ├── item_song.xml              # Song row layout
            │   └── item_album.xml            # Album card layout
            │
            ├── drawable/
            │   ├── ic_play.xml                # Play icon (vector)
            │   ├── ic_pause.xml               # Pause icon (vector)
            │   ├── ic_next.xml                # Next track icon (vector)
            │   ├── ic_previous.xml            # Previous track icon (vector)
            │   ├── ic_album.xml               # Album art placeholder (vector)
            │   └── ic_launcher_foreground.xml # Launcher icon foreground
            │
            ├── mipmap-anydpi-v26/
            │   ├── ic_launcher.xml            # Adaptive launcher icon (API 26+)
            │   └── ic_launcher_round.xml
            │
            ├── mipmap/
            │   ├── ic_launcher.xml            # Fallback launcher icon (API 24-25)
            │   └── ic_launcher_round.xml
            │
            └── values/
                ├── colors.xml                 # Color palette
                ├── strings.xml                # App strings
                ├── themes.xml                 # Material theme
                └── ids.xml                    # Pre-declared IDs (MediaRouteButton fix)
```

---

## 🛠️ Tech Stack & Dependencies

| Component | Version |
|---|---|
| Android Gradle Plugin | `8.5.2` |
| Gradle Wrapper | `8.9` |
| compileSdk | `34` (Android 14) |
| minSdk | `24` (Android 7.0) |
| targetSdk | `34` |
| Java | `17` |

### Libraries

| Library | Version | Purpose |
|---|---|---|
| `androidx.appcompat:appcompat` | `1.7.0` | Backward-compatible Activity/UI |
| `com.google.android.material:material` | `1.12.0` | Material Design components (TabLayout, CardView) |
| `androidx.constraintlayout:constraintlayout` | `2.1.4` | Layout positioning |
| `androidx.recyclerview:recyclerview` | `1.3.2` | Song/album lists |
| `androidx.viewpager2:viewpager2` | `1.1.0` | Swipeable Tracks/Albums tabs |
| `androidx.media:media` | `1.7.0` | MediaSession + MediaStyle notification |
| `androidx.mediarouter:mediarouter` | `1.7.0` | Audio output device selection (MediaRouteButton) |
| `com.github.bumptech.glide:glide` | `4.16.0` | Album cover art image loading |

---

## 📋 Permissions

| Permission | Reason | API Level |
|---|---|---|
| `READ_MEDIA_AUDIO` | Access music files on device | API 33+ |
| `READ_EXTERNAL_STORAGE` | Access music files (legacy) | API ≤ 32 |
| `FOREGROUND_SERVICE` | Run playback service in foreground | All |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Media playback foreground service type | API 34+ |
| `POST_NOTIFICATIONS` | Show playback notification | API 33+ |

---

## 🚀 Getting Started

### Prerequisites

- **Android Studio** (Hedgehog 2023.1.1 or newer recommended)
- **JDK 17** (bundled with recent Android Studio versions)
- An **Android device or emulator** running API 24+ with music files on it

### Build & Run

1. **Open the project**
   - Launch Android Studio → `File` → `Open` → select `F:\Project\androidMusicPlayer`

2. **Let Gradle sync**
   - Android Studio will automatically download Gradle 8.9 and all dependencies

3. **Run the app**
   - Connect your device or start an emulator
   - Click the **Run** button (▶) or press `Shift+F10`

4. **Grant permissions**
   - On first launch, the app will request audio access (and notification permission on API 33+)
   - Tap **Allow** — the track and album lists will populate

### Build from Command Line

```bash
# Debug build
./gradlew assembleDebug

# Install on connected device
./gradlew installDebug
```

---

## 🎼 How It Works

### Music Discovery

`MusicRepository` queries `MediaStore.Audio.Media.EXTERNAL_CONTENT_URI` with `IS_MUSIC != 0` to find all audio files. Songs are grouped by `ALBUM_ID` into `Album` objects using a `LinkedHashMap` (preserving insertion order). Album art URIs are built via `ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumId)`.

### Playback Architecture

```
UI (Activity/Fragment)
       ↕ callbacks
PlaybackManager (singleton)
       ↕ ServiceConnection
MusicService (foreground Service)
       ↕
MediaPlayer (Android framework)
```

- **`MusicService`** — A bound + started foreground service wrapping `MediaPlayer`. Manages a play queue, handles play/pause/next/prev/seek, builds a `MediaStyle` notification with album art and `MediaSessionCompat` metadata, and survives activity recreation.
- **`PlaybackManager`** — A singleton that binds to `MusicService` and exposes a simple callback API (`onPlaybackChanged`) so activities/fragments can react to state changes without directly managing the service connection.
- **`MediaSessionCompat`** — Provides metadata (title, artist, album, duration, album art bitmap) to the system for lock screen controls and media notification styling.

### Album Art Loading

- **UI (grid, detail, now playing):** Uses **Glide** to asynchronously load album art from the `albumart` content URI into `ImageView`s, with `ic_album` as placeholder/error fallback.
- **Notification:** `MusicService` loads the bitmap on a background `ExecutorService` via `Glide.asBitmap().submit(256, 256).get()`, then posts a notification refresh to the main thread via `Handler`. The bitmap is set both as `setLargeIcon()` on the notification and as `METADATA_KEY_ALBUM_ART` in `MediaMetadataCompat`.

### Audio Output Selection

A `MediaRouteButton` on the Now Playing screen is configured with a `MediaRouteSelector` supporting `CATEGORY_LIVE_AUDIO` and `CATEGORY_LIVE_VIDEO`. Tapping it opens the system's audio output picker, letting the user route playback to Bluetooth headphones, external speakers, Cast devices, etc.

### Seek Bar Synchronization

Both the mini player (MainActivity) and the Now Playing screen use a `Handler`-based periodic updater (every 500ms) to advance the seek bar during playback. A `userDragging` flag prevents the periodic updater and state-change callbacks from overriding the position while the user is dragging the thumb. The actual `seekTo()` call happens on `onStopTrackingTouch` only.

---

## 🧩 Key Classes

| Class | Role |
|---|---|
| `MainActivity` | Entry point. Hosts TabLayout + ViewPager2 with Tracks/Albums fragments. Manages runtime permissions and the mini player bar. |
| `TracksFragment` | RecyclerView listing all songs from MediaStore. Tap to play. |
| `AlbumsFragment` | Grid RecyclerView of albums with cover art. Tap to open AlbumDetailActivity. |
| `AlbumDetailActivity` | Shows album header (art + name + artist) and the album's song list. |
| `NowPlayingActivity` | Full-screen player with album art, seek bar, time labels, prev/play-pause/next, and MediaRouteButton. |
| `MusicService` | Foreground service with MediaPlayer, MediaSession, and MediaStyle notification. Handles the play queue and auto-advances on song completion. |
| `PlaybackManager` | Singleton bridge between UI and MusicService. Manages ServiceConnection and dispatches playback callbacks. |
| `MusicRepository` | Static methods to query MediaStore for songs and albums, and build album art URIs. |
| `SongAdapter` | RecyclerView adapter for individual song rows. |
| `AlbumAdapter` | RecyclerView adapter for album cards with Glide-loaded cover art. |
| `Song` / `Album` | Data models (`Song` is immutable; `Album` holds a mutable song list). |

---

## ⚠️ Known Notes

- **`attributionTag` warning**: Fixed by declaring `<attribution android:tag="audioPlayback" ...>` in the manifest as a direct child of `<manifest>`.
- **MediaRouteButton ID**: Pre-declared in `ids.xml` and referenced via `@id/` (not `@+id/`) to work around an AAPT2 conflict with the library's custom `id` styleable attribute.
- **`setAudioStreamType` deprecation**: `MusicService` uses `STREAM_MUSIC` via the deprecated `setAudioStreamType()` for simplicity. For production, consider migrating to `setAudioAttributes()` with `AudioAttributes.USAGE_MEDIA`.
- **No Gradle wrapper JAR**: The project includes `gradle-wrapper.properties` but not the `gradle-wrapper.jar` binary. Android Studio will generate it automatically on first sync. Alternatively run `gradle wrapper` from a local Gradle installation.

---

## 📄 License

This project is provided as-is for educational/demo purposes. Feel free to use and modify it freely.
