# simpleSOUND

Made this because I am tired of bad and confusing MP3 players on the market. Jezz, I just need a good mp3 player, I don't need a whole compact studio on my phone just to play some Ye's song.

A clean, dark-only Android MP3 player built with Jetpack Compose. The goal is a
calm, "flagship" listening experience inspired by Samsung's native music player —
no clutter, one accent color, roomy typography, and a tab strip you control.

> Status: **v0.1** — core navigation, library, playlists, favorites, and the
> Settings/Manage-tabs screens are in place. Playback is wired through Media3 and
> the on-device library is scanned via MediaStore (with sample data fallback).

## Features

### Tabs (user-configurable)
Six tabs are available; all can be toggled on/off and reordered in
**Settings → Manage tabs**, except **Tracks**, which is always enabled (it can
still be moved).

- **Favorites** — hearted tracks go into the always-present *Favorites tracks*
  playlist; hearted playlists also surface here.
- **Tracks** — every song on device. Sort by *Date added*, *Name*, *Artist*, or
  *Length*.
- **Playlists** — user-created playlists plus four native playlists
  (*Recently added*, *Most played*, *Recently played*, *Favorites tracks*),
  each capped at 100 tracks. Long-press a playlist to enter shake/drag mode and
  pick **Play / Add / Share / Remove**.
- **Albums** — grouped by album, with cover art.
- **Artists** — grouped by artist, drilling into their tracks.
- **Folders** — browse by filesystem folder.

### Playlists
First-class entity: create, **rename**, and **change cover**. Reorder via
long-press drag. Native playlists are auto-maintained and cannot be deleted.

### Theme
Dark is the only theme — the app never renders a light surface. The single knob
is the **accent color** (Teal, Violet, Coral, Amber, Rose, Lime, Sky, Sand),
selectable in Settings. The accent drives the active tab, toggles, play button,
and headers.

## Tech stack

| Layer | Choice |
|---|---|
| UI | Jetpack Compose + Material 3 |
| Navigation | Navigation-Compose |
| Playback | androidx.media3 (ExoPlayer + session) |
| Persistence | DataStore Preferences (settings, tab config) |
| Images | Coil |
| Library | MediaStore scanner with sample-data fallback |
| Min SDK | 26 (Android 8.0) |
| Target SDK | 34 |

## Project structure

```
app/src/main/java/com/simplesound/app/
├── MainActivity.kt              # entry point, permissions, theme + nav host
├── SimpleSoundApp.kt            # Application; owns SettingsStore
├── data/
│   ├── MediaStoreScanner.kt     # on-device track discovery
│   ├── MusicRepository.kt       # single source of truth for library/playlists
│   ├── SampleData.kt           # fallback content when no permission/files
│   ├── SettingsStore.kt         # accent + tab config persistence
│   └── model/                   # Track, Playlist, Tab, SortOption
├── playback/
│   ├── PlaybackService.kt       # Media3 session
│   └── PlayerController.kt     # local player handle
├── ui/
│   ├── AppViewModel.kt         # bridges settings + repository to Compose
│   ├── HomeScreen.kt           # dynamic tab strip + content switch
│   ├── LocalPlayer.kt         # CompositionLocal for PlayerController
│   ├── components/             # MiniPlayer, TrackRow, PlaylistGridCard, …
│   ├── navigation/             # SimpleSoundNavHost + Routes
│   ├── screens/
│   │   ├── albums/ artists/ folders/
│   │   ├── favorites/          # Favorites tab
│   │   ├── playlists/          # Playlists tab (grid + long-press)
│   │   ├── playlistdetail/     # single playlist view
│   │   ├── tracks/             # Tracks tab + sort header
│   │   └── settings/           # SettingsScreen + ManageTabsScreen
│   └── theme/                  # Color (AccentColor), Theme (dark-only), Type
└── util/Format.kt
```

## Building

This project uses the Gradle version catalog (`gradle/libs.versions.toml`).
Open in Android Studio (Ladyfish or newer) and let it sync, or from a terminal
with the Android SDK + a Gradle wrapper present:

```bash
./gradlew :app:assembleDebug
```

> Note: the Gradle wrapper is not committed in this snapshot. Generate it with
> `gradle wrapper` if you build from the command line.

## Design principles

1. **Dark by default, forever.** True-black background, no light variant.
2. **One accent.** A single user-chosen color carries every highlight.
3. **Roomy, not overwhelming.** Large tab typography, generous spacing, no
   secondary chrome competing for attention.
4. **Tabs belong to the user.** Order and visibility are theirs — except Tracks,
   which is the one guaranteed home for all music.