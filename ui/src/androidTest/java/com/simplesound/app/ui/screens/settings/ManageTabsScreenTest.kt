package com.simplesound.app.ui.screens.settings

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.simplesound.app.data.MusicRepository
import com.simplesound.app.data.SettingsStore
import com.simplesound.app.data.db.AppDatabase
import com.simplesound.app.data.model.Tab
import com.simplesound.app.ui.AppViewModel
import com.simplesound.app.ui.theme.SimpleSoundTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Exercises the "toggle a tab off" interaction end to end through a real
 * [AppViewModel] (backed by a real [SettingsStore] and an in-memory Room-backed
 * [MusicRepository], not mocks) — the ManageTabsScreen Switch is one of the
 * simplest, highest-confidence settings-toggle targets in the app.
 *
 * Tab.Default order is [FAVORITES, TRACKS, PLAYLISTS, ALBUMS, ARTISTS, FOLDERS],
 * and ManageTabsScreen renders one row per tab in that order, so the toggleable
 * nodes on screen line up with that index order.
 */
class ManageTabsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var vm: AppViewModel

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // SettingsStore's DataStore file name is fixed, so clear any state a
        // previous test run (or manual app use on this device) left behind —
        // otherwise these assertions could start from stale persisted tab state.
        File(context.filesDir, "datastore/simplesound_settings.preferences_pb").delete()

        val db =
            Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        val repository =
            MusicRepository(
                context,
                db.trackDao(),
                db.playlistDao(),
                db.playlistTrackDao(),
                db.favoriteDao(),
                db.playStatsDao(),
                db.customOrderDao(),
            )
        vm = AppViewModel(SettingsStore(context), repository)
    }

    @Test
    fun togglingFavoritesOffDisablesItWithoutAffectingTracks() {
        composeRule.setContent {
            SimpleSoundTheme { ManageTabsScreen(vm = vm, onBack = {}) }
        }

        val favoritesIndex = Tab.Default.indexOf(Tab.FAVORITES)
        composeRule.onAllNodes(isToggleable())[favoritesIndex].assertIsOn()

        composeRule.onAllNodes(isToggleable())[favoritesIndex].performClick()
        composeRule.waitForIdle()

        composeRule.onAllNodes(isToggleable())[favoritesIndex].assertIsOff()
        assertFalse(vm.tabSettings.value.first { it.tab == Tab.FAVORITES }.enabled)
        assertTrue(vm.tabSettings.value.first { it.tab == Tab.TRACKS }.enabled)
    }

    @Test
    fun tracksSwitchIsAlwaysOnAndNeverInteractive() {
        composeRule.setContent {
            SimpleSoundTheme { ManageTabsScreen(vm = vm, onBack = {}) }
        }

        val tracksIndex = Tab.Default.indexOf(Tab.TRACKS)
        composeRule.onAllNodes(isToggleable())[tracksIndex]
            .assertIsOn()
            .assertIsNotEnabled()
    }
}
