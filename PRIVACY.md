# simpleSOUND Privacy Policy

**Effective & last updated:** September 17, 2026
**Applies to:** package `com.simplesound.app` on Google Play

> This file is the canonical, published version of this policy. Link it as:
> `https://github.com/quinnwilder99/SimpleSound/blob/main/PRIVACY.md` — that's
> what's used in the Play Store listing (App content → Privacy policy) and in
> Settings → About in the app (`AboutScreen.kt`). Since it points at `main`,
> the link always resolves to whatever this file currently says; edit it here
> like any other source file.

## The short version

- simpleSOUND has no internet access — the app cannot send data anywhere,
  because it never requests a network connection (no `INTERNET` permission).
- Nothing is collected: no accounts, no analytics, no ads, no crash reporting,
  no tracking of any kind.
- Every playlist, favorite, and setting lives only in the app's private storage
  on your device.

## What this app does

simpleSOUND is a local music player. It reads the audio files already on your
device, plays them, and remembers your playlists, favorites, and preferences —
entirely on that device.

## Data we collect

None. simpleSOUND does not request the Android `INTERNET` permission at all,
which makes outbound data collection technically impossible, not just a policy
promise. The app contains no analytics SDK, no advertising SDK, and no
crash-reporting service.

## Permissions, and exactly what each one is for

| Permission | What it's for | Leaves your device? |
| --- | --- | --- |
| `READ_MEDIA_AUDIO` | Finds the songs already stored on your device so the library can be built and played. | No |
| `POST_NOTIFICATIONS` | Shows the playback notification (title, artist, play/pause/skip) while music is playing. | No |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Keeps music playing when the app is in the background, like every other music player. | No |
| `USE_FULL_SCREEN_INTENT` | Powers an optional lock-screen control bar (Settings → Edge control bar). Off by default; only used if you turn it on. | No |

## Where your data lives

The following stays in the app's private storage, protected by Android's own
app sandbox, and is never transmitted:

- Playlists, favorites, and play history — a local database on your device.
- The last-played track, queue, and resume position.
- Preferences such as accent color, tab layout, and crossfade duration.

If you use Android's own backup or phone-transfer feature, this data travels
the same way any app's private data does under your Google account's
settings — simpleSOUND itself has no part in, and no visibility into, that
process.

## Children's privacy

simpleSOUND is not directed at children and collects no data from anyone,
which includes children. There is nothing to collect, so there is nothing to
safeguard differently for younger users.

## Changes to this policy

If this policy ever changes — for example, if a future version adds a feature
that touches the network — the "last updated" date above will change and the
update will be described here before it ships.

## Contact

Questions about this policy: open an issue at
https://github.com/quinnwilder99/SimpleSound/issues
