# simpleSOUND

[![CI](https://github.com/quinnwilder99/SimpleSound/actions/workflows/ci.yml/badge.svg)](https://github.com/quinnwilder99/SimpleSound/actions/workflows/ci.yml)

> A clean, offline-first Android MP3 player built with Jetpack Compose and a modular architecture.

Made this because I am tired of bad and confusing MP3 players on the market. Jeez, I just need a good MP3 player — I don't need a whole compact studio on my phone just to play some Ye's songs.

SimpleSound is a dark-only Android music player focused on a calm, flagship listening experience inspired by Samsung's native music player. The goal is simplicity: no unnecessary features, no clutter, one accent color, roomy typography, and a tab system controlled by the user.

> Status: **v1.2.0** — Core navigation, library management, playlists, favorites, search, sleep timer, crossfade, and Media3 playback are all implemented. The app uses a multi-module architecture with offline-first data management, dependency injection, and separated playback/UI layers.

---

# Features

## Music Library

* Automatically scans the device music library using MediaStore
* Offline-first local music access
* Sample-data fallback when no songs are available
* Sort tracks by:

  * Date added
  * Name
  * Artist
  * Length
  * **Custom order** — drag to reorder tracks by hand, order is remembered

## Search

* Instant search across the whole library from any tab
* Multi-select results directly from the search screen (select all / deselect, share, delete, add to playlist)

## Tabs (User Configurable)

Six tabs are available and can be enabled, disabled, and reordered through:

```
Settings → Manage Tabs
```

Except **Tracks**, which is always enabled.

Available tabs:

* **Favorites**

  * Favorite tracks
  * Favorite playlists

* **Tracks**

  * Complete device music library

* **Playlists**

  * User-created playlists
  * Automatically maintained playlists:

    * Recently added
    * Most played
    * Recently played
    * Favorites tracks

* **Albums**

  * Album grouping with artwork

* **Artists**

  * Artist grouping with track browsing

* **Folders**

  * Filesystem-based browsing

## Multi-Select Actions

Long-press any track to enter selection mode across Tracks, Albums, Artists, Folders, Playlists, and Search:

* Play selected tracks
* Add to playlist
* Share
* Remove from playlist / Delete
* Select all / Deselect all

---

# Playback

Powered by AndroidX Media3.

Features:

* Background playback service with persistent lock-screen / notification controls
* Media session integration
* ExoPlayer-based audio engine
* **Queue** — view and reorder the current play queue on the fly without touching the source playlist
* **Crossfade** — smoothly blend the end of one track into the start of the next, with adjustable duration (0–12s, presets or custom)
* **Sleep timer** — auto-pause playback after a preset or custom duration (up to 12 hours), with a live countdown
* Mini player that follows you across tabs, expandable into the full Now Playing screen

---

# Architecture

SimpleSound uses a **multi-module Clean Architecture approach** to separate responsibilities, improve maintainability, and allow independent development of major components.

## Module Structure

```
SimpleSound
│
├── app
│   └── Application entry point
│   └── Hilt initialization
│   └── Android configuration
│
├── ui
│   └── Jetpack Compose screens
│   └── Navigation
│   └── ViewModels
│   └── UI components
│
├── data
│   └── Repository layer
│   └── MediaStore integration
│   └── Room database
│   └── DataStore preferences
│
├── playback
│   └── Media3 / ExoPlayer integration
│   └── PlaybackService
│   └── PlayerController
│
└── core
    └── Shared models
    └── Common utilities
    └── Theme definitions
```

## Module Dependency Flow

```
                 app
                  |
        ---------------------
        |        |          |
       ui      data     playback
        |        |          |
        -------- core -------
```

Responsibilities:

### app

Application bootstrap layer.

Handles:

* Application lifecycle
* Hilt setup
* Android entry points

---

### ui

Responsible for:

* Jetpack Compose UI
* Screens
* Navigation
* ViewModels
* User interactions

The UI layer does not directly manage storage or playback.

---

### data

Responsible for:

* Local data sources
* Repository pattern
* Music library scanning
* Persistence
* Auto-syncing the library when device media changes, via a `ContentObserver` on
  `MediaStore.Audio.Media` that enqueues a `WorkManager` job to rescan and update
  Room — no user action required

Uses:

* Room (indexed track/playlist/favorites/play-stats tables)
* DataStore
* MediaStore
* WorkManager

---

### playback

Responsible for:

* Audio playback engine
* Media3 integration
* Background playback
* Player lifecycle

---

### core

Contains shared code used across modules:

* Models
* Constants
* Utilities
* Theme components

---

# Tech Stack

| Layer                | Technology                      |
| --------------------- | -------------------------------- |
| Language              | Kotlin                           |
| UI                    | Jetpack Compose + Material 3     |
| Architecture          | Multi-module Clean Architecture  |
| Dependency Injection  | Hilt                              |
| Navigation            | Navigation Compose               |
| Playback              | AndroidX Media3 + ExoPlayer      |
| Database              | Room                              |
| Preferences           | DataStore                         |
| Async                 | Kotlin Coroutines + Flow          |
| Images                | Coil                               |
| Library Scanner       | MediaStore + ContentObserver       |
| Background Sync       | WorkManager (HiltWorker)          |
| Build System          | Gradle Kotlin DSL                 |
| Static Analysis       | ktlint + detekt                   |
| CI/CD                 | GitHub Actions                     |
| Min SDK               | 26                                 |
| Target SDK            | 34                                 |

---

# Design Principles

## 1. Dark by default, forever

SimpleSound intentionally uses a dark-only design.

The app avoids:

* Bright surfaces
* Visual clutter
* Excessive UI elements

## 2. Liquid glass UI

Key surfaces — the mini player, headers, and other "hero" moments — use a soft, frosted glass material inspired by Apple's Liquid Glass: a diffuse light-to-dark sheen, a faint accent-tinted glow, and a hairline rim, all drawn in pure Compose (no blur pass needed).

Used sparingly on purpose — the glass effect is reserved for one or two standout surfaces per screen so it still reads as something special, instead of being applied to every row in a list.

## 3. One accent color

The user chooses a single accent color:

* Teal
* Violet
* Coral
* Amber
* Rose
* Lime
* Sky
* Sand

The accent drives:

* Active tabs
* Buttons
* Toggles
* Headers
* The liquid glass gloss tint

## 4. Offline-first experience

Music playback should not depend on the internet.

The app prioritizes:

* Local storage
* Fast library access
* Reliable playback
* Persistent user settings

## 5. Modular by design

The application is split into independent modules to:

* Improve build times
* Reduce coupling
* Make testing easier
* Separate feature ownership
* Allow future expansion

---

# Building

This project uses:

* Gradle Kotlin DSL
* Version Catalog (`gradle/libs.versions.toml`)

Build debug APK:

```bash
./gradlew :app:assembleDebug
```

Deploy to a connected phone as an in-place update (keeps playlists + play
history) — see [DEPLOY.md](DEPLOY.md):

```powershell
powershell -ExecutionPolicy Bypass -File scripts\deploy.ps1
```

Run unit tests (Repository + ViewModel, JVM/Robolectric):

```bash
./gradlew test
```

Run Compose UI tests + Room migration tests (needs a connected device/emulator):

```bash
./gradlew :ui:connectedAndroidTest :data:connectedAndroidTest
```

`:data:connectedAndroidTest` runs `AppDatabaseMigrationTest`, which replays every
Room schema migration and must pass before shipping any DB schema change (see
[DEPLOY.md](DEPLOY.md)).

Run static analysis:

```bash
./gradlew ktlintCheck detekt
```

Run Android Lint:

```bash
./gradlew :app:lintDebug
```

Every push/PR to `main` runs the full set above (ktlint, detekt, unit tests, lint,
`assembleDebug`) via [GitHub Actions](.github/workflows/ci.yml). detekt findings that
predate its adoption are grandfathered in `config/detekt/baseline-*.xml`; new code
is held to the clean baseline.

---

# Future Improvements

Planned features:

* Gapless playback
* Audio equalizer
* Wear OS companion app
* Home screen widget
* Lyrics support

---

# License

This project is currently a personal portfolio project.
