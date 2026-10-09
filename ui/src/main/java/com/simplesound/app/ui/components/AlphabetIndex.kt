package com.simplesound.app.ui.components

import kotlin.math.roundToInt

/**
 * Buckets track titles into A–Z sections for [AlphabetScrollbar].
 *
 * The buckets mirror the Name sort exactly (`String.CASE_INSENSITIVE_ORDER` on
 * the raw title, see MusicRepository.comparatorFor): that comparator orders the
 * first character by `lowercase(uppercase(c))`, so anything below 'a' (digits,
 * punctuation, '_', '[', a blank title, ...) sorts before A and anything above
 * 'z' (accented letters like "Á"/"Đ", other scripts, '{', ...) sorts after Z.
 * Keeping the same rule guarantees the slots are non-decreasing down a
 * name-sorted list, so "first item at or after slot X" is always a valid jump.
 */
internal object AlphabetIndex {
    /** Titles that sort before A ("#"). */
    const val HEAD = 0

    /** Titles that sort after Z (accented / non-Latin first letters). */
    const val TAIL = 27

    fun slotOf(title: String): Int {
        val k = title.firstOrNull()?.uppercaseChar()?.lowercaseChar() ?: return HEAD
        return when {
            k < 'a' -> HEAD
            k <= 'z' -> 1 + (k - 'a')
            else -> TAIL
        }
    }

    /** The big letter shown in the bubble for an item with this title. */
    fun labelOf(title: String): String =
        when (val slot = slotOf(title)) {
            HEAD -> "#"
            TAIL -> String(Character.toChars(title.codePointAt(0))).uppercase()
            else -> slotLabel(slot)
        }

    /** The small label drawn on the bar for a slot. */
    fun slotLabel(slot: Int): String =
        when (slot) {
            HEAD -> "#"
            TAIL -> "…"
            else -> ('A' + (slot - 1)).toString()
        }

    /** The slots the bar spans: A–Z always, plus "#" / tail only when the list has such titles. */
    fun barSlots(itemSlots: IntArray): List<Int> =
        buildList {
            if (itemSlots.any { it == HEAD }) add(HEAD)
            addAll(1..26)
            if (itemSlots.any { it == TAIL }) add(TAIL)
        }

    /** First item whose slot is [slot] or later; the last item if nothing is that far down. */
    fun targetIndex(
        itemSlots: IntArray,
        slot: Int,
    ): Int {
        val i = itemSlots.indexOfFirst { it >= slot }
        return if (i >= 0) i else itemSlots.lastIndex
    }

    /** Evenly spread a few labels over [slotCount] slots; always includes the first and last. */
    fun labelPositions(
        slotCount: Int,
        labelCount: Int,
    ): List<Int> {
        val n = labelCount.coerceIn(1, slotCount.coerceAtLeast(1))
        return when {
            slotCount <= 0 -> emptyList()
            n == 1 -> listOf(0)
            else -> (0 until n).map { i -> (i * (slotCount - 1).toFloat() / (n - 1)).roundToInt() }.distinct()
        }
    }
}
