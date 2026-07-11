# simpleSOUND

> A clean, offline-first Android MP3 player built with Jetpack Compose and a modular architecture.

Made this because I am tired of bad and confusing MP3 players on the market. Jeez, I just need a good MP3 player — I don't need a whole compact studio on my phone just to play some Ye's songs.

SimpleSound is a dark-only Android music player focused on a calm, flagship listening experience inspired by Samsung's native music player. The goal is simplicity: no unnecessary features, no clutter, one accent color, roomy typography, and a tab system controlled by the user.

> Status: **v0.1** — Core navigation, library management, playlists, favorites, settings, and Media3 playback are implemented. The application uses a multi-module architecture with offline-first data management, dependency injection, and separated playback/UI layers.

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

---

# Playback

Powered by AndroidX Media3.

Features:

* Background playback service
* Media session integration
* Persistent playback control
* ExoPlayer-based audio engine

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

Uses:

* Room
* DataStore
* MediaStore

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
| -------------------- | ------------------------------- |
| Language             | Kotlin                          |
| UI                   | Jetpack Compose + Material 3    |
| Architecture         | Multi-module Clean Architecture |
| Dependency Injection | Hilt                            |
| Navigation           | Navigation Compose              |
| Playback             | AndroidX Media3 + ExoPlayer     |
| Database             | Room                            |
| Preferences          | DataStore                       |
| Async                | Kotlin Coroutines + Flow        |
| Images               | Coil                            |
| Library Scanner      | MediaStore                      |
| Build System         | Gradle Kotlin DSL               |
| Min SDK              | 26                              |
| Target SDK           | 34                              |

---

# Design Principles

## 1. Dark by default, forever

SimpleSound intentionally uses a dark-only design.

The app avoids:

* Bright surfaces
* Visual clutter
* Excessive UI elements

---

## 2. One accent color

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

---

## 3. Offline-first experience

Music playback should not depend on the internet.

The app prioritizes:

* Local storage
* Fast library access
* Reliable playback
* Persistent user settings

---

## 4. Modular by design

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

Run tests:

```bash
./gradlew test
```

Run lint:

```bash
./gradlew lint
```

---

# Future Improvements

Planned features:

* Gapless playback
* Crossfade transitions
* Audio equalizer
* Wear OS companion app
* Home screen widget
* Advanced playlist management

---

# License

This project is currently a personal portfolio project.
