package com.simplesound.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

/**
 * The on-device SQLite database holding everything the user would be upset to lose
 * on an app update: their playlists and playlist ordering, hearted tracks, and
 * per-track play stats (which drive "Recently played" / "Most played"). The file
 * (`simplesound.db`) lives in the app's private data dir and is untouched by
 * `adb install -r`, so a normal feature update keeps all of it.
 *
 * The ONE thing that breaks that guarantee is changing the schema — adding a
 * column, a table, an index, changing a type — without giving Room a [Migration].
 * When that happens Room throws `IllegalStateException` on first launch after the
 * update and the app won't start. (It does NOT silently wipe data — there is
 * deliberately no `fallbackToDestructiveMigration()` anywhere; see [DatabaseModule].)
 *
 * ### Checklist for ANY change to an @Entity in this package
 * 1. Bump [version] by one.
 * 2. Build once (`./gradlew :data:kspDebugKotlin`) so a new `data/schemas/<n>.json`
 *    is generated, and commit it.
 * 3. Add a `Migration(n-1, n)` to [MIGRATIONS] with the exact `ALTER TABLE` /
 *    `CREATE TABLE` / `CREATE INDEX` SQL. A purely-additive change to one entity
 *    can instead use `@Database(autoMigrations = [AutoMigration(from = n-1, to = n)])`.
 * 4. Run `./gradlew :data:connectedAndroidTest` — `AppDatabaseMigrationTest`
 *    replays every migration against the real historical schemas and fails if the
 *    result doesn't match what Room expects.
 * 5. Only then deploy. See DEPLOY.md.
 */
@Database(
    entities = [
        TrackEntity::class,
        PlaylistEntity::class,
        PlaylistTrackCrossRef::class,
        FavoriteTrackEntity::class,
        PlayStatsEntity::class,
        CustomOrderEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao

    abstract fun playlistDao(): PlaylistDao

    abstract fun playlistTrackDao(): PlaylistTrackDao

    abstract fun favoriteDao(): FavoriteDao

    abstract fun playStatsDao(): PlayStatsDao

    abstract fun customOrderDao(): CustomOrderDao

    companion object {
        const val NAME = "simplesound.db"

        /**
         * Every schema migration, in order. Wired into the builder in [DatabaseModule].
         * Empty while the schema is still at version 1 — add to it per the checklist
         * in this file's KDoc. Never remove or reorder an entry once shipped.
         */
        val MIGRATIONS: Array<Migration> = emptyArray()
    }
}
