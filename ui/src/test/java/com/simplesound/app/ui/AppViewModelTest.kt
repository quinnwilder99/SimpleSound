package com.simplesound.app.ui

import android.content.Context
import app.cash.turbine.test
import com.simplesound.app.data.DEFAULT_PLAYLIST_SORT
import com.simplesound.app.data.MusicRepository
import com.simplesound.app.data.SettingsStore
import com.simplesound.app.data.model.SortOption
import com.simplesound.app.data.model.Tab
import com.simplesound.app.data.model.TabSetting
import com.simplesound.core.theme.AccentColor
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [AppViewModel] does almost no logic of its own beyond bridging [SettingsStore]/
 * [MusicRepository] flows to the UI and enforcing that the Tracks tab can never be
 * disabled — this test fakes both dependencies and asserts exactly that bridging
 * and that one business rule.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    private lateinit var settings: SettingsStore
    private lateinit var repository: MusicRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        settings =
            mockk(relaxed = true) {
                every { accent } returns flowOf(AccentColor.Default)
                every { tabSettings } returns flowOf(Tab.Default.map { TabSetting(it, true) })
                every { tracksSort } returns flowOf(SortOption.DATE_ADDED)
                every { crossfadeSeconds } returns flowOf(0)
            }
        repository =
            mockk(relaxed = true) {
                every { tracks } returns MutableStateFlow(emptyList())
                every { userPlaylists } returns MutableStateFlow(emptyList())
                every { favoriteTrackIds } returns MutableStateFlow(emptySet())
                every { favoritesTabPlaylists } returns MutableStateFlow(emptyList())
            }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = AppViewModel(settings, repository)

    @Test
    fun `tracks userPlaylists and favorites are passed through from the repository`() =
        runTest {
            val vm = viewModel()
            assertEquals(repository.tracks.value, vm.tracks.value)
            assertEquals(repository.userPlaylists.value, vm.userPlaylists.value)
            assertEquals(repository.favoriteTrackIds.value, vm.favoriteTrackIds.value)
            assertEquals(repository.favoritesTabPlaylists.value, vm.favoritesTabPlaylists.value)
        }

    @Test
    fun `accent reflects the settings flow`() =
        runTest {
            val vm = viewModel()
            vm.accent.test {
                assertEquals(AccentColor.Default, awaitItem())
            }
        }

    @Test
    fun `setTabSettings forces the mandatory Tracks tab to stay enabled`() =
        runTest {
            val vm = viewModel()
            val allDisabled = Tab.entries.map { TabSetting(it, enabled = false) }

            vm.setTabSettings(allDisabled)
            dispatcher.scheduler.advanceUntilIdle()

            coVerify {
                settings.setTabSettings(
                    match { list -> list.first { it.tab == Tab.TRACKS }.enabled },
                )
            }
        }

    @Test
    fun `setTabSettings leaves non-mandatory tabs as requested`() =
        runTest {
            val vm = viewModel()
            val allDisabled = Tab.entries.map { TabSetting(it, enabled = false) }

            vm.setTabSettings(allDisabled)
            dispatcher.scheduler.advanceUntilIdle()

            coVerify {
                settings.setTabSettings(
                    match { list -> list.none { it.tab != Tab.TRACKS && it.enabled } },
                )
            }
        }

    @Test
    fun `loadDeviceLibrary delegates to the repository`() =
        runTest {
            val vm = viewModel()
            val context = mockk<Context>()
            coEvery { repository.loadDeviceLibrary(context) } returns Unit

            vm.loadDeviceLibrary(context)

            // loadDeviceLibrary launches on the real Dispatchers.IO (deliberately,
            // so the calling thread never blocks on a MediaStore scan), so it runs
            // on a genuine background thread outside this test's virtual-time
            // dispatcher — advanceUntilIdle() can't wait for it. Poll instead.
            coVerify(timeout = 2_000) { repository.loadDeviceLibrary(context) }
        }

    @Test
    fun `toggleFavoriteTrack delegates to the repository`() {
        val vm = viewModel()
        vm.toggleFavoriteTrack(7L)
        verify { repository.toggleFavoriteTrack(7L) }
    }

    @Test
    fun `createPlaylist delegates to the repository and returns its id`() {
        every { repository.createPlaylist("Road trip", emptyList()) } returns "user-123"
        val vm = viewModel()

        val id = vm.createPlaylist("Road trip")

        assertEquals("user-123", id)
        verify { repository.createPlaylist("Road trip", emptyList()) }
    }

    @Test
    fun `playlistSort seeds from the cache after being set once`() =
        runTest {
            every { settings.playlistSort("p1", DEFAULT_PLAYLIST_SORT) } returns flowOf(SortOption.NAME)
            val vm = viewModel()

            vm.setPlaylistSort("p1", SortOption.NAME)
            dispatcher.scheduler.advanceUntilIdle()

            vm.playlistSort("p1").test {
                assertEquals(SortOption.NAME, awaitItem())
            }
        }

    @Test
    fun `miniPlayerHidden defaults to false and can be toggled`() {
        val vm = viewModel()
        assertFalse(vm.miniPlayerHidden.value)
        vm.setMiniPlayerHidden(true)
        assertTrue(vm.miniPlayerHidden.value)
    }
}
