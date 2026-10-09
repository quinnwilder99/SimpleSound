package com.simplesound.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlphabetIndexTest {
    @Test
    fun `letters bucket case-insensitively`() {
        assertEquals(1, AlphabetIndex.slotOf("apple"))
        assertEquals(1, AlphabetIndex.slotOf("Apple"))
        assertEquals(26, AlphabetIndex.slotOf("zebra"))
        assertEquals("M", AlphabetIndex.labelOf("moon"))
    }

    @Test
    fun `titles that sort before A go to the hash bucket`() {
        listOf("", " lead", "1999", "_intro", "[live]", "!bang").forEach {
            assertEquals("'$it'", AlphabetIndex.HEAD, AlphabetIndex.slotOf(it))
            assertEquals("#", AlphabetIndex.labelOf(it))
        }
    }

    @Test
    fun `titles that sort after Z go to the tail bucket and show their own letter`() {
        assertEquals(AlphabetIndex.TAIL, AlphabetIndex.slotOf("Đường xa"))
        assertEquals("Đ", AlphabetIndex.labelOf("đường xa"))
        assertEquals(AlphabetIndex.TAIL, AlphabetIndex.slotOf("{brace}"))
    }

    @Test
    fun `slots never go backwards down a list in Name sort order`() {
        // Same comparator as MusicRepository's SortOption.NAME.
        val titles =
            listOf(
                "zebra", "Apple", "_intro", "1999", "Đường xa", "banana", "[live]", "Ánh nắng",
                "", "yellow", "{x}", "Mango", "ılık", "日本", "🎵 note", "a", "Z",
            ).sortedWith(String.CASE_INSENSITIVE_ORDER)
        val slots = titles.map(AlphabetIndex::slotOf)
        assertEquals(slots.sorted(), slots)
    }

    @Test
    fun `target is first item at or after the slot, else the last item`() {
        // #, A, A, C, tail
        val itemSlots = intArrayOf(AlphabetIndex.HEAD, 1, 1, 3, AlphabetIndex.TAIL)
        assertEquals(0, AlphabetIndex.targetIndex(itemSlots, AlphabetIndex.HEAD))
        assertEquals(1, AlphabetIndex.targetIndex(itemSlots, 1))
        assertEquals(3, AlphabetIndex.targetIndex(itemSlots, 2)) // no B -> next is C
        assertEquals(4, AlphabetIndex.targetIndex(itemSlots, 26)) // no Z -> tail
        assertEquals(1, AlphabetIndex.targetIndex(intArrayOf(1, 5), AlphabetIndex.TAIL))
    }

    @Test
    fun `bar spans A to Z plus hash and tail only when present`() {
        assertEquals((1..26).toList(), AlphabetIndex.barSlots(intArrayOf(1, 2)))
        val all = AlphabetIndex.barSlots(intArrayOf(AlphabetIndex.HEAD, 4, AlphabetIndex.TAIL))
        assertEquals(28, all.size)
        assertEquals(AlphabetIndex.HEAD, all.first())
        assertEquals(AlphabetIndex.TAIL, all.last())
    }

    @Test
    fun `only a few evenly spread labels, always first and last`() {
        val positions = AlphabetIndex.labelPositions(26, 6)
        assertEquals(listOf(0, 5, 10, 15, 20, 25), positions)
        assertEquals("AFKPUZ", positions.joinToString("") { AlphabetIndex.slotLabel(it + 1) })
        assertTrue(AlphabetIndex.labelPositions(26, 2) == listOf(0, 25))
    }
}
