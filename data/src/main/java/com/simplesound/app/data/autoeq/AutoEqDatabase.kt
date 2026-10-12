package com.simplesound.app.data.autoeq

import android.content.Context
import android.util.JsonReader
import com.simplesound.app.data.model.EqFilter
import com.simplesound.app.data.model.EqFilterType
import com.simplesound.app.data.model.HeadphoneCorrection
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Reader
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The AutoEq headphone corrections bundled at `assets/autoeq/autoeq_profiles.json`
 * (built by scripts/autoeq/build_autoeq_db.py, refreshed monthly by CI).
 *
 * Loaded lazily -- only the equalizer's headphone picker needs the whole list; the
 * chosen correction itself is copied into the equalizer settings, so playback never
 * touches this file. Streamed with [JsonReader] rather than parsed into a tree, since
 * it's a few MB of JSON.
 */
@Singleton
class AutoEqDatabase
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val mutex = Mutex()
        private var cached: List<HeadphoneCorrection>? = null

        /** Every headphone, sorted by name. Empty if the asset is missing or unreadable (retried next call). */
        suspend fun headphones(): List<HeadphoneCorrection> =
            mutex.withLock {
                cached ?: withContext(Dispatchers.IO) {
                    runCatching {
                        context.assets.open(ASSET).bufferedReader().use { parse(it) }
                    }.getOrDefault(emptyList())
                }.also { if (it.isNotEmpty()) cached = it }
            }

        companion object {
            private const val ASSET = "autoeq/autoeq_profiles.json"
            private const val SCHEMA_VERSION = 1

            internal fun parse(reader: Reader): List<HeadphoneCorrection> {
                val json = JsonReader(reader)
                var profiles = emptyList<HeadphoneCorrection>()
                json.beginObject()
                while (json.hasNext()) {
                    when (json.nextName()) {
                        "schemaVersion" -> check(json.nextInt() == SCHEMA_VERSION) { "Unsupported AutoEq schema" }
                        "profiles" -> profiles = readProfiles(json)
                        else -> json.skipValue()
                    }
                }
                json.endObject()
                return profiles
            }

            private fun readProfiles(json: JsonReader): List<HeadphoneCorrection> {
                val out = ArrayList<HeadphoneCorrection>(INITIAL_CAPACITY)
                json.beginArray()
                while (json.hasNext()) readProfile(json)?.let(out::add)
                json.endArray()
                return out
            }

            private fun readProfile(json: JsonReader): HeadphoneCorrection? {
                var id = ""
                var name = ""
                var type = ""
                var source = ""
                var preamp = 0.0
                var filters = emptyList<EqFilter>()
                json.beginObject()
                while (json.hasNext()) {
                    when (json.nextName()) {
                        "id" -> id = json.nextString()
                        "name" -> name = json.nextString()
                        "type" -> type = json.nextString()
                        "source" -> source = json.nextString()
                        "preamp" -> preamp = json.nextDouble()
                        "filters" -> filters = readFilters(json)
                        else -> json.skipValue()
                    }
                }
                json.endObject()
                if (id.isEmpty() || filters.isEmpty()) return null
                return HeadphoneCorrection(id, name, type, source, preamp, filters)
            }

            private fun readFilters(json: JsonReader): List<EqFilter> {
                val out = ArrayList<EqFilter>()
                json.beginArray()
                while (json.hasNext()) {
                    var type: EqFilterType? = null
                    var fc = 0.0
                    var gain = 0.0
                    var q = 0.0
                    json.beginObject()
                    while (json.hasNext()) {
                        when (json.nextName()) {
                            "type" -> {
                                val name = json.nextString()
                                type = EqFilterType.entries.firstOrNull { it.name == name }
                            }
                            "fc" -> fc = json.nextDouble()
                            "gain" -> gain = json.nextDouble()
                            "q" -> q = json.nextDouble()
                            else -> json.skipValue()
                        }
                    }
                    json.endObject()
                    if (type != null && fc > 0 && q > 0) out += EqFilter(type, fc, gain, q)
                }
                json.endArray()
                return out
            }

            private const val INITIAL_CAPACITY = 8192
        }
    }
