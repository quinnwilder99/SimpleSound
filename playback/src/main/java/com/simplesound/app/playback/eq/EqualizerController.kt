package com.simplesound.app.playback.eq

import com.simplesound.app.data.SettingsStore
import com.simplesound.app.data.autoeq.AutoEqDatabase
import com.simplesound.app.data.model.EqualizerSettings
import com.simplesound.app.data.model.HeadphoneCorrection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Live equalizer state shared by the UI and the audio chain (UI and
 * PlaybackService run in the same process). Edits land in [settings] immediately,
 * so every [EqualizerAudioProcessor] hears a slider move within one buffer, and are
 * persisted to [SettingsStore] once the user pauses, rather than on every drag frame.
 */
@Singleton
class EqualizerController
    @Inject
    constructor(
        private val settingsStore: SettingsStore,
        private val database: AutoEqDatabase,
    ) {
        // Main: load, edits and persistence all run on one thread, so an edit made
        // before the stored settings arrive can't race with (and be overwritten by) them.
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private val _settings = MutableStateFlow(EqualizerSettings())
        val settings: StateFlow<EqualizerSettings> = _settings.asStateFlow()
        private var edited = false

        init {
            scope.launch { loadThenPersist() }
        }

        @OptIn(FlowPreview::class)
        private suspend fun loadThenPersist() {
            // An unreadable store shouldn't take persistence down with it; start from defaults.
            val stored = runCatching { settingsStore.equalizer.first() }.getOrDefault(EqualizerSettings())
            // An edit made while loading wins over the stored value (and gets saved below).
            if (!edited) _settings.value = stored
            var saved = stored
            _settings.debounce(PERSIST_DEBOUNCE_MS).collect { latest ->
                if (latest != saved) {
                    runCatching { settingsStore.setEqualizer(latest) }.onSuccess { saved = latest }
                }
            }
        }

        private fun edit(transform: (EqualizerSettings) -> EqualizerSettings) {
            edited = true
            _settings.update(transform)
        }

        fun setEnabled(enabled: Boolean) = edit { it.copy(enabled = enabled) }

        /** Picking a headphone (or a band/preset) implies the user wants to hear it, so it also turns the EQ on. */
        fun setHeadphone(headphone: HeadphoneCorrection?) =
            edit { it.copy(headphone = headphone, enabled = it.enabled || headphone != null) }

        fun setBandGain(
            band: Int,
            gainDb: Float,
        ) = edit { current ->
            val max = EqualizerSettings.MAX_BAND_GAIN_DB
            val gains = current.bandGainsDb.toMutableList().also { it[band] = gainDb.coerceIn(-max, max) }
            current.copy(bandGainsDb = gains, enabled = true)
        }

        fun setBandGains(gainsDb: List<Float>) =
            edit { it.copy(bandGainsDb = gainsDb, enabled = it.enabled || gainsDb.any { g -> g != 0f }) }

        /** Every bundled AutoEq headphone; loads the database on first call. */
        suspend fun headphones(): List<HeadphoneCorrection> = database.headphones()

        private companion object {
            const val PERSIST_DEBOUNCE_MS = 400L
        }
    }
