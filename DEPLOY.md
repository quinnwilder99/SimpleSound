# Deploying to your phone

**Goal:** every build you put on your phone is an *update*. It keeps your
playlists, favorites, play history ("Recently played" / "Most played"), the
mini-player resume point, and your settings. It only changes the app's code.

## The one command

```powershell
powershell -ExecutionPolicy Bypass -File scripts\deploy.ps1
```

That's it. The script builds `:app:assembleDebug`, checks it is safe to install
over what's already on the phone, and runs `adb install -r` (reinstall, keep
data). It refuses and explains itself if anything would cause data loss.

Options:

| Flag | Meaning |
| --- | --- |
| `-Serial <serial>` | Pick a device when more than one is connected. |
| `-SkipBuild` | Install the APK already in `app/build/outputs/apk/debug/`. |
| `-Force` | Proceed even though `data/schemas/` changed (only if a Room `Migration` is in place, see below). |

> Commit these changes first: the schema guard compares `data/schemas/` against
> git, so `data/schemas/1.json` must be committed or every run needs `-Force`.

## Why your data survives an update

| Data | Where it lives | Survives `adb install -r`? |
| --- | --- | --- |
| Playlists, playlist order, playlist covers | Room DB `simplesound.db` | Yes — private files are untouched by a reinstall |
| Favorite tracks | Room DB | Yes |
| Play counts / last-played (drives Recently&nbsp;/&nbsp;Most played) | Room DB (`play_stats` table) | Yes |
| Custom per-playlist track order | Room DB | Yes |
| Last-played track + queue (resume point) | SharedPreferences `simplesound_playlists.xml` | Yes |
| Accent color, tabs, sort, crossfade | DataStore `simplesound_settings` | Yes |

`adb install -r` replaces only the APK. It never touches
`/data/data/com.simplesound.app/`. The database and preference files stay exactly
as they were.

## The three things that DO destroy data — and how they're prevented

### 1. A different signing key → forced uninstall

If a build is signed with a different certificate than the one on the phone,
Android rejects it (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`) and the only way in is
`adb uninstall` first — which wipes everything.

**Prevented by:** the debug build is signed with a fixed keystore committed to the
repo — `keystore/simplesound-debug.keystore` — wired up in `app/build.gradle.kts`.
Every machine, every build, same signature. (It's the standard AOSP debug key,
password `android`; not a secret.) Don't delete that file. Don't add a `release`
signing config and start shipping release builds without reading this section
again.

### 2. A lower `versionCode` → downgrade install

Installing a build whose `versionCode` is lower than the one on the phone fails
unless you pass `-d` (downgrade), and on many devices `-d` still wipes data.

**Prevented by:** `versionCode` is computed from the git commit count
(`git rev-list --count HEAD`, floored at `versionCodeFloor` in
`app/build.gradle.kts`). It only ever goes up, and it goes up on its own every
time you commit. The deploy script also reads the phone's current `versionCode`
and refuses to install anything not strictly higher.

If you ever squash/rewrite history such that the commit count drops, bump
`versionCodeFloor` to at least the highest value ever installed.

### 3. A Room schema change without a migration → crash on launch

If you add/remove/rename a column, table, or index in any `@Entity` and bump the
DB version without giving Room a `Migration`, the app throws on first launch after
the update. (It does **not** silently wipe — there is deliberately no
`fallbackToDestructiveMigration()`.) Still: a crash-on-launch is not "just update
the features".

**Checklist when you touch anything in `data/.../db/`:**

1. Bump `version` in `@Database(...)` on `AppDatabase`, and `latestVersion` in
   `AppDatabaseMigrationTest`.
2. Build once so the new `data/schemas/<n>.json` is generated. Commit it.
3. Add a `Migration(n-1, n)` to `AppDatabase.MIGRATIONS` with the exact SQL
   (`ALTER TABLE …`, `CREATE TABLE …`, `CREATE INDEX …`).
4. Run the migration test on a device/emulator:
   ```powershell
   ./gradlew :data:connectedAndroidTest
   ```
   `AppDatabaseMigrationTest` replays every migration against the real historical
   schemas and fails if the end state doesn't match what Room expects.
5. Deploy. The deploy script warns if `data/schemas/` changed; pass `-Force` once
   you've done steps 1–4.

## Never do these

- `adb uninstall com.simplesound.app` — deletes all playlists and history.
- `adb install -d …` / `adb install -r -d …` — downgrade, can wipe data.
- "Clear data" / "Clear storage" in Android Settings for the app.
- Delete `keystore/simplesound-debug.keystore` or change the debug signing config.
- Add `fallbackToDestructiveMigration()` to the Room builder.
- Ship a schema change (new DB `version`) without a `Migration` + updated schema JSON.

## First-time setup on a new machine

Nothing — the keystore and everything else is in the repo. Just connect the phone
(USB debugging on) and run `powershell -ExecutionPolicy Bypass -File scripts\deploy.ps1`.

## Building for the Play Store (a different signing key)

Everything above is about the debug build that gets sideloaded onto your own
phone. The Play Store needs a `release` build signed with a **separate,
private** upload key — never the committed debug key (Play rejects uploads
signed with the well-known debug certificate outright).

1. Generate the key once (already done for this project — see below if you
   need to redo it):
   ```powershell
   keytool -genkeypair -v -keystore keystore/simplesound-release.keystore `
     -alias simplesound-release -keyalg RSA -keysize 2048 -validity 10000
   ```
2. Create `keystore.properties` at the repo root (gitignored, never commit it):
   ```
   storeFile=keystore/simplesound-release.keystore
   storePassword=...
   keyAlias=simplesound-release
   keyPassword=...
   ```
   `app/build.gradle.kts` picks this up automatically and wires a `release`
   `signingConfig` when the file is present; it's simply absent on CI / fresh
   clones, which is fine since CI only runs `assembleRelease` to make sure the
   release build type still compiles, not to upload it anywhere.
3. Build the artifact Play actually wants (an `.aab`, not an `.apk`):
   ```powershell
   ./gradlew :app:bundleRelease
   ```
   Output: `app/build/outputs/bundle/release/app-release.aab`.
4. **Back up `keystore/simplesound-release.keystore` and the two passwords in
   `keystore.properties` somewhere durable outside this repo** (password
   manager, encrypted drive) the moment you generate them. If you lose them,
   Play can never accept an update signed with the same upload key again —
   recoverable only through Play App Signing's key-reset support process,
   which is slow and not guaranteed. This key is *not* like the debug
   key: it is a real secret and must never be committed.
5. In Play Console, enroll in **Play App Signing** when you create the app —
   Google then re-signs your upload with its own app signing key for
   distribution, and the upload key above only needs to authenticate you as
   the uploader.

If the phone already has a SimpleSound build that was signed with that machine's
old per-machine `~/.android/debug.keystore`, the first deploy from the new setup
will hit a signature mismatch. The script stops rather than uninstalling. Recover
the original keystore if you can; otherwise you have to uninstall once
(losing that install's data) and start clean — do it deliberately, by hand.
