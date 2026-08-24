package com.simplesound.app.ui.screens.settings

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.simplesound.app.data.MusicRepository
import com.simplesound.app.data.SettingsStore
import com.simplesound.app.data.db.AppDatabase
import com.simplesound.app.ui.AppViewModel
import com.simplesound.app.ui.theme.SimpleSoundTheme
import com.simplesound.app.ui.theme.label
import com.simplesound.core.theme.AccentColor
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Exercises picking an accent color end to end through a real [AppViewModel]
 * (backed by a real [SettingsStore], not a mock) — tapping a swatch should update
 * the ViewModel's exposed [AppViewModel.accent] state.
 */
class AccentColorScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var vm: AppViewModel

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Clear any accent persisted by a previous test run so this test starts
        // from the known default rather than whatever was last saved on-device.
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
    fun tappingASwatchUpdatesTheViewModelsAccent() {
        val target = AccentColor.entries.first { it != AccentColor.Default }

        composeRule.setContent {
            SimpleSoundTheme { AccentColorScreen(vm = vm, onBack = {}) }
        }

        composeRule.onNodeWithText(target.label).performClick()
        composeRule.waitUntil(timeoutMillis = 2_000) { vm.accent.value == target }

        assertEquals(target, vm.accent.value)
    }
}
