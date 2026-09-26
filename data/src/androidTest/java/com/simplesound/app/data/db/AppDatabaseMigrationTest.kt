package com.simplesound.app.data.db

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/**
 * Guards the "an update keeps my playlists and history" promise.
 *
 * [migrateFromV1ToLatest] recreates the database at every historical schema and
 * replays [AppDatabase.MIGRATIONS] forward, letting Room assert the end result is
 * byte-for-byte the schema it expects — this fails if a migration's SQL is wrong
 * or missing. [latestSchemaOpensCleanly] then opens the current schema through the
 * real production builder.
 *
 * Needs a device/emulator: `./gradlew :data:connectedAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {
    private val testDbName = "migration-test.db"

    // Bump in lockstep with @Database(version = ...) on AppDatabase.
    private val latestVersion = 2

    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            AppDatabase::class.java,
        )

    @Test
    @Throws(IOException::class)
    @Suppress("SpreadOperator")
    fun migrateFromV1ToLatest() {
        // Create at the first shipped schema...
        helper.createDatabase(testDbName, 1).close()

        // ...then apply every migration up to today and let Room validate the
        // schema. With no migrations yet this just confirms v1 is self-consistent;
        // once MIGRATIONS is non-empty it exercises each step.
        if (latestVersion > 1) {
            helper.runMigrationsAndValidate(
                testDbName,
                latestVersion,
                true,
                *AppDatabase.MIGRATIONS,
            )
        }
    }

    @Test
    @Throws(IOException::class)
    fun migrate1To2KeepsTracksFavoritesAndPlaylists() {
        helper.createDatabase(testDbName, 1).apply {
            execSQL(
                "INSERT INTO tracks (id, title, artist, album, durationMs, uri, albumArtUri, folder, dateAddedSec) " +
                    "VALUES (7, 'Song', 'Artist', 'Album', 1000, 'content://x/7', NULL, 'Music', 123)",
            )
            execSQL("INSERT INTO favorite_tracks (trackId) VALUES (7)")
            execSQL(
                "INSERT INTO playlists (id, name, coverUri, favorited, favoritedAt, position) " +
                    "VALUES ('p', 'Mix', NULL, 0, 0, 0)",
            )
            execSQL("INSERT INTO playlist_track_cross_ref (playlistId, trackId, position) VALUES ('p', 7, 0)")
            close()
        }

        val db = helper.runMigrationsAndValidate(testDbName, 2, true, AppDatabase.MIGRATION_1_2)
        db.query("SELECT title, path, missingSinceSec FROM tracks WHERE id = 7").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Song", c.getString(0))
            assertEquals("", c.getString(1))
            assertEquals(0L, c.getLong(2))
        }
        db.query("SELECT COUNT(*) FROM favorite_tracks").use { c ->
            c.moveToFirst()
            assertEquals(1, c.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM playlist_track_cross_ref").use { c ->
            c.moveToFirst()
            assertEquals(1, c.getInt(0))
        }
        db.close()
    }

    @Test
    fun latestSchemaOpensCleanly() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val builder = Room.databaseBuilder(context, AppDatabase::class.java, "latest-open-test.db")
        AppDatabase.MIGRATIONS.forEach { builder.addMigrations(it) }
        val db = builder.build()
        try {
            runBlocking {
                // Run a real statement against every table so Room binds each
                // one's columns — a schema/entity mismatch throws here.
                db.trackDao().insertAll(emptyList())
                db.playlistDao().getAll()
                db.playlistTrackDao().getAll()
                db.favoriteDao().getAll()
                db.playStatsDao().getAll()
                db.customOrderDao().getAll()
            }
            assertTrue(db.isOpen)
        } finally {
            db.close()
            context.deleteDatabase("latest-open-test.db")
        }
    }
}
