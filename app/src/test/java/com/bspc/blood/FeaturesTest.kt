package com.bspc.blood

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** ফিচার সুইচ (Features.kt): সার্ভারের উত্তর পড়া, on(), ফোনে রাখা আর আবার পড়া, feature_off চেনা, "শিগগির আসছে" */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FeaturesTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        AppPrefs.lang.value = Lang.BN
        Features.init(ctx)
        Features.clear()
    }

    @Test
    fun parsesTheServerAnswer() {
        val r = JSONObject("""{"keys":{"chat":true,"calls_audio":false,"online_chamber":"true"},"v":7}""")
        val m = Features.parse(r)
        assertEquals(true, m["chat"])
        assertEquals(false, m["calls_audio"])
        assertEquals("only a real true counts", false, m["online_chamber"])
        assertTrue(Features.parse(JSONObject("{}")).isEmpty())
    }

    @Test
    fun offByDefaultAndOnAfterSet() {
        for (k in Features.KEYS) assertFalse(k, Features.on(k))
        Features.set(mapOf(Features.CHAT to true, Features.CALLS_AUDIO to false), 3, "u1")
        assertTrue(Features.on(Features.CHAT))
        assertFalse(Features.on(Features.CALLS_AUDIO))
        assertFalse(Features.on("unknown"))
        assertEquals(true, Features.keys.value[Features.CHAT])
        assertEquals(3L, Features.version)
    }

    @Test
    fun theLastKeysSurviveARestart() {
        Features.set(mapOf(Features.ONLINE_CHAMBER to true), 9, "u1")
        // নতুন প্রসেস: শুধু ফোনে রাখা থেকে
        Features.init(ctx)
        assertTrue(Features.on(Features.ONLINE_CHAMBER))
        assertEquals(9L, Features.version)
        Features.clear()
        Features.init(ctx)
        assertFalse(Features.on(Features.ONLINE_CHAMBER))
    }

    @Test
    fun knowsTheServerFeatureOffError() {
        assertTrue(Features.isFeatureOff(ServerError("এই সুবিধা এখনো চালু হয়নি", "feature_off")))
        assertFalse(Features.isFeatureOff(ServerError("x", "other")))
        assertFalse(Features.isFeatureOff(IllegalStateException("x")))
    }

    @Test
    fun soonText() {
        assertEquals("এই সুবিধা শিগগির আসছে", Features.soonText())
        assertEquals(24, Features.KEYS.size)
    }
}
