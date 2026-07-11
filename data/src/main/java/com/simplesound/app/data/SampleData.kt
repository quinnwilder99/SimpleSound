package com.simplesound.app.data

import com.simplesound.app.data.model.Playlist
import com.simplesound.app.data.model.PlaylistKind
import com.simplesound.app.data.model.Track

/**
 * Placeholder library used until real device audio is scanned (or when running
 * on an emulator with no media). Lets every screen render immediately.
 */
object SampleData {

    val tracks: List<Track> = buildList {
        val seeds = listOf(
            Triple("Butter Pecan", "YNW Melly", "We All Shine"),
            Triple("Alarm", "YNW Melly", "We All Shine"),
            Triple("Control Me", "YNW Melly", "We All Shine"),
            Triple("Curtain", "YNW Melly", "We All Shine"),
            Triple("Ingredients", "YNW Melly", "We All Shine"),
            Triple("Mixed Personalities", "YNW Melly", "We All Shine"),
            Triple("POWER", "Kanye West", "My Beautiful Dark Twisted Fantasy"),
            Triple("Runaway", "Kanye West", "My Beautiful Dark Twisted Fantasy"),
            Triple("Stronger", "Kanye West", "Graduation"),
            Triple("25 Or 6 To 4", "Chicago", "Chicago II"),
            Triple("500 Miles", "Peter, Paul and Mary", "Moving"),
            Triple("Lay All Your Love On Me", "ABBA", "Super Trouper"),
            Triple("Achy Breaky Heart", "Billy Ray Cyrus", "Some Gave All"),
            Triple("Ain't No Love", "Bobby Bland", "His California Album"),
            Triple("Hollow Knight Main Theme", "Christopher Larkin", "Hollow Knight OST"),
            Triple("Dirtmouth", "Christopher Larkin", "Hollow Knight OST"),
            Triple("Nocturne No. 2", "Frédéric Chopin", "Nocturnes"),
            Triple("Clair de Lune", "Claude Debussy", "Suite Bergamasque"),
        )
        seeds.forEachIndexed { i, (title, artist, album) ->
            add(
                Track(
                    id = (i + 1).toLong(),
                    title = title,
                    artist = artist,
                    album = album,
                    durationMs = (150_000L + (i * 7_919L) % 130_000L),
                    uri = "",
                    folder = when {
                        album.contains("Hollow") -> "Internal/Music/GameOST"
                        artist == "Frédéric Chopin" || artist == "Claude Debussy" -> "Internal/Music/Classical"
                        else -> "Internal/Music/Downloads"
                    },
                    dateAddedSec = 1_720_000_000L - i * 3_600L,
                    playCount = (seeds.size - i) * 3,
                    lastPlayedSec = 1_720_000_000L - i * 1_800L
                )
            )
        }
    }

    fun userPlaylists(): List<Playlist> = listOf(
        Playlist(
            id = "seed-goat", name = "GOAT",
            trackIds = tracks.map { it.id }.take(12), kind = PlaylistKind.USER, favorited = true
        ),
        Playlist(
            id = "seed-classical", name = "Classical",
            trackIds = tracks.filter { it.album in listOf("Nocturnes", "Suite Bergamasque") }.map { it.id },
            kind = PlaylistKind.USER, favorited = true
        ),
        Playlist(
            id = "seed-rockmetal", name = "RockMetal",
            trackIds = tracks.map { it.id }.takeLast(6), kind = PlaylistKind.USER
        ),
        Playlist(
            id = "seed-hollow", name = "Hollow Knight OST",
            trackIds = tracks.filter { it.album == "Hollow Knight OST" }.map { it.id },
            kind = PlaylistKind.USER
        ),
    )

    /** Track ids hearted by default so the Favorites tab is not empty on first run. */
    val favoriteTrackIds: Set<Long> = setOf(1L, 7L, 9L, 15L)
}
