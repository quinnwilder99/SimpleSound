package com.simplesound.app.data

import com.simplesound.app.data.model.EqFilter
import com.simplesound.app.data.model.EqFilterType
import com.simplesound.app.data.model.EqualizerSettings
import com.simplesound.app.data.model.HeadphoneCorrection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EqualizerCodecTest {
    private val headphone =
        HeadphoneCorrection(
            id = "sennheiser-hd-650",
            name = "Sennheiser HD 650 (2020, worn earpads)",
            type = "over-ear",
            source = "oratory1990",
            preampDb = -6.4,
            filters =
                listOf(
                    EqFilter(EqFilterType.LSC, 105.0, 5.5, 0.7),
                    EqFilter(EqFilterType.PK, 2458.0, -3.1, 2.05),
                    EqFilter(EqFilterType.HSC, 10000.0, -1.0, 0.7),
                ),
        )

    @Test
    fun `headphone survives a round trip`() {
        assertEquals(headphone, EqualizerCodec.decodeHeadphone(EqualizerCodec.encodeHeadphone(headphone)))
    }

    @Test
    fun `missing or corrupt headphone decodes to none`() {
        assertNull(EqualizerCodec.decodeHeadphone(null))
        assertNull(EqualizerCodec.decodeHeadphone(""))
        assertNull(EqualizerCodec.decodeHeadphone("just a name"))
        val broken = EqualizerCodec.encodeHeadphone(headphone).replace("2458.0", "abc")
        assertNull(EqualizerCodec.decodeHeadphone(broken))
    }

    @Test
    fun `bands round trip and are clamped`() {
        val gains = listOf(12f, -12f, 0.5f, 0f, 0f, 0f, 0f, 0f, -3.5f, 1f)
        assertEquals(gains, EqualizerCodec.decodeBands(EqualizerCodec.encodeBands(gains)))
        assertEquals(12f, EqualizerCodec.decodeBands("30,0,0,0,0,0,0,0,0,0")[0])
    }

    @Test
    fun `malformed bands fall back to flat`() {
        val flat = EqualizerSettings().bandGainsDb
        assertEquals(flat, EqualizerCodec.decodeBands(null))
        assertEquals(flat, EqualizerCodec.decodeBands("1,2,3"))
        assertEquals(flat, EqualizerCodec.decodeBands("1,2,3,4,5,6,7,8,9,x"))
    }
}
