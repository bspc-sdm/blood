package com.lifolo.unit

import com.lifolo.common.Phones
import com.lifolo.common.Places
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Expected values from server/lib/care.js, hospital-match.js and districts.js */
class PlacesPhonesTest {
    @Test
    fun `phone normalization like care js`() {
        val cases = listOf(
            Triple("01712345678", "8801712345678", "01712345678"),
            Triple("+8801712345678", "8801712345678", "01712345678"),
            Triple("8801712345678", "8801712345678", "01712345678"),
            Triple("1712345678", "8801712345678", "01712345678"),
            Triple("0171234567", null, null),
            Triple("02-9887766", null, "029887766"),
            Triple("16263", null, "16263"),
            Triple("+880 2 9887766", null, "029887766"),
            Triple("00171", null, null),
        )
        for ((raw, bd, contact) in cases) {
            assertEquals(bd, Phones.normalizeBd(raw), raw)
            assertEquals(contact, Phones.contactPhone(raw), raw)
        }
        assertNull(Phones.normalizeBd(null))
        assertEquals("01712345678", Phones.fixPhone("001712345678"))
        assertEquals("ঢাকামেট্রোগ123456", Phones.normId("ঢাকা মেট্রো-গ ১২-৩৪৫৬"))
        assertEquals("DL12AB", Phones.normId("dl-12/ab"))
    }

    @Test
    fun `bundled places`() {
        assertEquals(64, Places.DISTRICTS.size)
        assertEquals("ময়মনসিংহ", Places.DISTRICTS.first { it.en == "Mymensingh" }.bn)
        assertEquals(Places.Upazila(466, "ময়মনসিংহ", "ময়মনসিংহ সদর", "Mymensingh Sadar"), Places.UPAZILA[466])
        assertEquals(Places.Union(4254, 463, "ধানীখোলা", "Dhanikhola"), Places.UNION[4254])
        assertTrue(Places.isDistrict("ময়মনসিংহ"))
        assertEquals(466, Places.upazilaIn("ময়মনসিংহ", 466))
        assertNull(Places.upazilaIn("ঢাকা", 466))
        assertTrue(Places.UPAZILA_CENTER.containsKey(1))
    }
}
