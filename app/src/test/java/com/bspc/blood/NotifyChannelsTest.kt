package com.bspc.blood

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** ফোনের চ্যানেল: প্রতিটা ধরনের একটা, ঠিক গুরুত্বে, বাংলা নামে; আগের একমাত্র চ্যানেল মুছে যায় */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotifyChannelsTest {
    @Test
    fun channelsCreatedAndOldOneRemoved() {
        val ctx: Context = RuntimeEnvironment.getApplication()
        val nm = ctx.getSystemService(NotificationManager::class.java)
        // ২.৩৫ পর্যন্ত: একটাই চ্যানেল
        nm.createNotificationChannel(NotificationChannel(NotifyCats.OLD_CHANNEL_ID, "Blood requests", NotificationManager.IMPORTANCE_HIGH))
        AppPrefs.lang.value = Lang.BN

        Notifier.ensureChannels(ctx)

        assertNull(nm.getNotificationChannel(NotifyCats.OLD_CHANNEL_ID))
        NotifyCats.ALL.forEach { cat ->
            val ch = nm.getNotificationChannel(NotifyCats.channelId(cat))
            assertEquals(cat, NotifyCats.label(cat), ch.name.toString())
            val want = if (cat in NotifyCats.HIGH) NotificationManager.IMPORTANCE_HIGH else NotificationManager.IMPORTANCE_DEFAULT
            assertEquals(cat, want, ch.importance)
        }
        assertEquals("জরুরি রক্তের আহ্বান", nm.getNotificationChannel("cat_blood_emergency").name.toString())
        assertEquals("অ্যাকাউন্ট আর নিরাপত্তা (সবসময় চালু)", nm.getNotificationChannel("cat_account").name.toString())
        assertEquals(NotifyCats.ALL.size, nm.notificationChannels.count { it.id.startsWith("cat_") })

        // আবার ডাকলে (ভাষা বদলালে) নাম বদলায়, চ্যানেল বাড়ে না
        AppPrefs.lang.value = Lang.EN
        Notifier.ensureChannels(ctx)
        assertEquals(NotifyCats.ALL.size, nm.notificationChannels.count { it.id.startsWith("cat_") })
        assertEquals("Ambulance", nm.getNotificationChannel("cat_ambulance").name.toString())
        AppPrefs.lang.value = Lang.BN
    }
}
