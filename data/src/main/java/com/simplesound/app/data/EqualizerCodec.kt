package com.simplesound.app.data

import com.simplesound.app.data.model.EqFilter
import com.simplesound.app.data.model.EqFilterType
import com.simplesound.app.data.model.EqualizerSettings
import com.simplesound.app.data.model.HeadphoneCorrection

/**
 * Flat-string encodings for [EqualizerSettings] in DataStore. The chosen headphone
 * correction is stored whole (not just its id) so PlaybackService can apply it
 * without loading the multi-MB AutoEq database.
 */
internal object EqualizerCodec {
    // ASCII unit/record separators: never appear in headphone names.
    private const val FIELD = '\u001F'
    private const val RECORD = '\u001E'

    fun encodeBands(gains: List<Float>): String = gains.joinToString(",")

    fun decodeBands(raw: String?): List<Float> {
        val default = EqualizerSettings().bandGainsDb
        val max = EqualizerSettings.MAX_BAND_GAIN_DB
        val parsed = raw?.split(",")?.map { it.toFloatOrNull()?.coerceIn(-max, max) }
        return parsed?.takeIf { it.size == default.size && null !in it }?.filterNotNull() ?: default
    }

    fun encodeHeadphone(h: HeadphoneCorrection): String =
        buildString {
            append(listOf(h.id, h.name, h.type, h.source, h.preampDb.toString()).joinToString(FIELD.toString()))
            h.filters.forEach { f ->
                append(RECORD)
                append(listOf(f.type.name, f.frequencyHz, f.gainDb, f.q).joinToString(FIELD.toString()))
            }
        }

    /** Null when [raw] is absent or doesn't decode cleanly (any bad field drops the whole correction). */
    fun decodeHeadphone(raw: String?): HeadphoneCorrection? {
        val records = raw?.takeIf { it.isNotEmpty() }?.split(RECORD).orEmpty()
        val head = records.firstOrNull()?.split(FIELD)?.takeIf { it.size == HEAD_FIELDS }
        val preamp = head?.get(4)?.toDoubleOrNull()
        val filters = records.drop(1).map(::decodeFilter).takeIf { it.isNotEmpty() && null !in it }
        return if (head == null || preamp == null || filters == null) {
            null
        } else {
            HeadphoneCorrection(head[0], head[1], head[2], head[3], preamp, filters.filterNotNull())
        }
    }

    private fun decodeFilter(record: String): EqFilter? {
        val f = record.split(FIELD).takeIf { it.size == FILTER_FIELDS } ?: return null
        val type = EqFilterType.entries.firstOrNull { it.name == f[0] }
        val numbers = f.drop(1).mapNotNull { it.toDoubleOrNull() }
        return if (type == null || numbers.size != FILTER_FIELDS - 1) {
            null
        } else {
            EqFilter(type, numbers[0], numbers[1], numbers[2])
        }
    }

    private const val HEAD_FIELDS = 5
    private const val FILTER_FIELDS = 4
}
