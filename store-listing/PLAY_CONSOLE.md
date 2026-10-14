# Publishing simpleSOUND on Google Play

What to enter in Play Console, form by form. The build side (target API 36,
16 KB page alignment, release signing) is already handled in the repo. The
items below live in Play Console or need a phone.

## 0. Before you start

- **Personal developer account created after Nov 13, 2023?** Then before you
  can publish to Production, you must run a **closed test with at least 12
  testers opted in for 14 days in a row**. Production access is applied for
  from the Dashboard once that's done. Plan for this; it is the longest step.
- **Your phone's current sideloaded build can't be updated from Play.** It is
  signed with the debug key and Play installs are signed by Google, so Android
  treats them as different apps. Installing from Play means uninstalling the
  sideloaded build first, which deletes its playlists and history.
- Back up `keystore/simplesound-release.keystore` and `keystore.properties`
  outside this repo (see DEPLOY.md). Losing them locks you out of updates.

## 1. Build and upload

```powershell
./gradlew :app:bundleRelease
```

Upload `app/build/outputs/bundle/release/app-release.aab`. Accept **Play App
Signing** when asked. `versionCode` comes from the git commit count, so commit
before each upload; every upload needs a higher one.

## 2. Store listing (Grow → Store presence → Main store listing)

**App name** (max 30): `simpleSOUND`

**Short description** (max 80):

> A calm, offline music player for the songs already on your phone.

**Full description** (max 4000):

> simpleSOUND is a music player for the audio files already on your phone. No
> account, no streaming, no ads, and no internet access at all. Open it and
> your library is there.
>
> LIBRARY
> • Tracks, Albums, Artists, Folders, Playlists and Favorites tabs. Turn tabs
>   on or off and reorder them.
> • Sort by date added, name, artist or length, or drag tracks into your own order.
> • Instant search across your whole library.
> • Long-press to select several songs at once: play, add to a playlist, share or delete.
> • Edit an MP3's title, artist and album right inside the file.
>
> PLAYLISTS
> • Make your own playlists, with your own cover photo.
> • Automatic playlists: Recently added, Most played, Recently played, Favorites.
>
> PLAYBACK
> • Background playback with lock-screen and notification controls.
> • Picks up where you left off, even after the app is closed. Your headset's
>   play button works too.
> • Queue you can reorder on the fly.
> • Crossfade between songs (0–12 seconds).
> • 10-band equalizer with presets, plus headphone correction for thousands of
>   headphone models, built in and offline.
> • Sleep timer.
>
> DESIGN
> • Dark, quiet interface with one accent color you choose.
>
> PRIVACY
> simpleSOUND collects nothing. It has no internet permission, so your music,
> playlists and listening history never leave your phone.

**Graphics** (files in this folder):

| Asset | File | Status |
| --- | --- | --- |
| App icon 512×512 | `icon-512.png` | Ready |
| Feature graphic 1024×500 | `feature-graphic.png` | Ready |
| Phone screenshots (2–8) | — | **You need to take these** on your phone: Tracks tab, Now Playing, a playlist, Equalizer. Portrait, no status-bar notifications showing. |

**Category:** Music & Audio. **Contact email:** required and shown publicly.
Use one you're fine publishing.

## 3. App content (Policy → App content)

| Form | Answer |
| --- | --- |
| Privacy policy | `https://github.com/quinnwilder99/SimpleSound/blob/main/PRIVACY.md` (keep the repo public, or the link breaks and the app gets rejected) |
| Ads | No, my app does not contain ads |
| App access | All functionality is available without special access |
| Content rating | Category **All other app types**. Answer **No** to every question: no violence, no user-to-user sharing or chat, no purchases, no location sharing. Expected rating: Everyone / PEGI 3 |
| Target audience | **13 and over** (13–15, 16–17, 18+). Answer that the app is not designed to appeal to children. Including under-13s opts you into the stricter Families policy for no benefit |
| News app | No |
| Data safety | "Does your app collect or share any of the required user data types?" → **No**. Everything stays on the device, which Play does not count as collection. Leave encryption and deletion blank; they only apply when data is collected |
| Advertising ID | No, the app doesn't use advertising ID |
| Government app | No |
| Financial features | None |
| Health | None |

### Foreground service permissions (required, needs a video)

Type: **Media playback**. Use case: *Continue audio playback from the background*.

> Description: simpleSOUND is a music player. While the user is playing music
> they started, a mediaPlayback foreground service keeps the audio going when
> the app is in the background or the screen is off, with a media notification
> to pause, skip or stop. The service stops when playback is stopped.
>
> Impact if deferred: the music the user is listening to would stop as soon
> as they leave the app or turn off the screen.

**Video:** record about 30 seconds of screen capture showing: open the app →
tap a song → press Home → music keeps playing → pull down the notification
and pause it. Upload it to YouTube as **Unlisted** and paste the link.

### Full-screen intent permission

Answer that the app's core functionality is **not** calling or alarms (pick
the "other" option). The optional lock-screen control bar then needs the user
to allow it, which the app already handles: Settings → Edge control bar shows
an "Allow full screen notifications" row, and the bar stays off until it's
allowed.

## 4. Release

1. Testing → **Closed testing** → create a track, upload the AAB, add your 12+
   testers (Google Group or email list), roll out.
2. After 14 days with 12+ testers opted in: Dashboard → **Apply for production**.
3. Production → create release → same AAB (or a newer one) → countries → roll out.

Check the **Pre-launch report** on every upload. It installs the build on real
devices and flags crashes and accessibility issues.
