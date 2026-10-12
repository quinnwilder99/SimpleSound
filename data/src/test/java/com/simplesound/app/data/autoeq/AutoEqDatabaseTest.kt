package com.simplesound.app.data.autoeq

import com.simplesound.app.data.model.EqFilterType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.StringReader

// Robolectric for android.util.JsonReader.
@RunWith(RobolectricTestRunner::class)
class AutoEqDatabaseTest {
    @Test
    fun `parses profiles and skips unusable entries`() {
        val json =
            """
            {"schemaVersion":1,"autoeqCommit":"abc","profiles":[
              {"id":"a","name":"A","type":"in-ear","source":"crinacle","preamp":-6.0,
               "filters":[{"type":"LSC","fc":105.0,"gain":5.5,"q":0.7},{"type":"XX","fc":1,"gain":1,"q":1}]},
              {"id":"b","name":"B","type":"over-ear","source":"oratory1990","preamp":-1.0,"filters":[]}
            ]}
            """.trimIndent()
        val profiles = AutoEqDatabase.parse(StringReader(json))
        assertEquals(1, profiles.size)
        assertEquals("A", profiles[0].name)
        assertEquals(-6.0, profiles[0].preampDb, 0.0)
        assertEquals(listOf(EqFilterType.LSC), profiles[0].filters.map { it.type })
    }

    /** Guards the bundled asset itself: a bad monthly refresh PR fails CI here. */
    @Test
    fun `bundled database parses`() {
        val asset = File("src/main/assets/autoeq/autoeq_profiles.json")
        val profiles = asset.bufferedReader().use { AutoEqDatabase.parse(it) }
        assertTrue("expected thousands of headphones, got ${profiles.size}", profiles.size > 1_000)
        assertEquals(profiles.size, profiles.map { it.id }.toSet().size)
        assertTrue(profiles.any { it.name == "Sennheiser HD 650" })
    }
}
