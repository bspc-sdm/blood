package com.bspc.blood

import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * "আজকের কাজ"-এর হিসাব (WorkToday.kt): backend-এর `work_today` পড়া (না এলে খালি), ফিচার সুইচ বন্ধে সংখ্যা/কাজ বাদ,
 * পুরোনো সার্ভারে নিজের তথ্য থেকে অবস্থা, সংখ্যার ঘর, "এখনই দেখুন"-এর তালিকা, কোন ভাগ খুলবে, "আজকের মতো" বিরতি।
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorkTodayTest {
    private val now = 1_761_278_400_000L // ২৪ অক্টোবর ২০২৫ ১০:০০ ঢাকা (০৪:০০ UTC)

    @Before
    fun setUp() {
        Areas.ensure(ApplicationProvider.getApplicationContext())
        AppPrefs.lang.value = Lang.BN
        Features.set(Features.KEYS.associateWith { true }, 1L, null)
        WorkStatusApi.forgetUnknown()
    }

    @After
    fun tearDown() {
        Features.set(emptyMap(), 0L, null)
        AppPrefs.lang.value = Lang.BN
    }

    private fun task(kind: String, type: String, id: String, title: String, action: String, at: Long) =
        JSONObject().put("kind", kind).put("bizId", "b").put("type", type).put("id", id).put("title", title).put("sub", "").put("at", at).put("action", action).put("link", "/work")

    /** backend-এর চূড়ান্ত গঠন (CONTRACT.md "Changes", ২৪ অক্টোবর) */
    private fun sample(): JSONObject = JSONObject()
        .put("now", now).put("roles", JSONArray(listOf("doctor", "pharmacy", "bloodbank", "ambulance")))
        .put(
            "items",
            JSONArray()
                .put(
                    JSONObject().put("kind", "doctor").put("id", "d1").put("name", "ডা. ডেমো হাসান").put("role", "owner").put("listingStatus", JSONObject.NULL).put("status", "open")
                        .put("counts", JSONObject().put("today", 5).put("waiting", 2).put("requests", 1).put("onlineToday", 2))
                        .put("setup", JSONArray())
                        .put(
                            "chambers",
                            JSONArray()
                                .put(JSONObject().put("chamberIndex", 0).put("chamberKey", "k0").put("name", "Popular").put("nameBn", "পপুলার").put("sitsToday", true).put("closedToday", false).put("status", "open"))
                                .put(JSONObject().put("chamberIndex", 1).put("chamberKey", "k1").put("name", "MMCH").put("nameBn", "মমেক").put("sitsToday", true).put("status", "paused").put("pausedUntil", now + 1_800_000))
                                .put(JSONObject().put("chamberIndex", 2).put("chamberKey", "k2").put("name", "Far").put("sitsToday", false).put("closedToday", false).put("status", "closed")),
                        )
                        .put("tasks", JSONArray().put(task("doctor", "appt_request", "a1", "করিমের বুকিং", "accept", now - 60_000))),
                )
                .put(
                    JSONObject().put("kind", "pharmacy").put("id", "u1").put("name", "ডেমো লাইফ ফার্মেসি").put("listingStatus", "approved").put("status", "paused").put("pausedUntil", now + 1_800_000)
                        .put("counts", JSONObject().put("new", 3).put("active", 1).put("salesToday", 7).put("salesTodayTk", 1200.5).put("invoicesToday", 4).put("finds", 2))
                        .put(
                            "tasks",
                            JSONArray()
                                .put(task("pharmacy", "order_new", "o1", "রহিমের প্রেসক্রিপশন", "accept", now - 600_000))
                                .put(task("pharmacy", "pay_check", "o2", "পেমেন্ট মেলানো", "pay", now))
                                .put(JSONObject().put("id", "x").put("title", "")),
                        )
                        .put("setup", JSONArray().put(JSONObject().put("key", "approved").put("done", true)).put(JSONObject().put("key", "hours").put("done", false))),
                )
                .put(JSONObject().put("kind", "bloodbank").put("id", "b1").put("name", "সন্ধানী").put("listingStatus", "approved").put("status", JSONObject.NULL).put("stockUpdatedAt", now - 3_600_000).put("stockStale", false))
                .put(
                    JSONObject().put("kind", "ambulance").put("id", "own").put("name", "অ্যাম্বুলেন্স").put("status", "open").put("counts", JSONObject().put("calls", 1).put("trips", 0))
                        .put(
                            "vehicles",
                            JSONArray()
                                .put(JSONObject().put("id", "a1").put("name", "জীবন").put("listingStatus", "approved").put("available", true))
                                .put(JSONObject().put("id", "a2").put("name", "আইসিইউ").put("listingStatus", "approved").put("available", false))
                                .put(JSONObject().put("id", "a3").put("name", "নতুন").put("listingStatus", "pending").put("available", true)),
                        ),
                ),
        )
        .put("tasks", JSONArray().put(task("pharmacy", "pay_check", "o2", "পেমেন্ট মেলানো", "pay", now)).put(task("pharmacy", "order_new", "o1", "রহিমের প্রেসক্রিপশন", "accept", now - 600_000)).put(task("doctor", "appt_request", "a1", "করিমের বুকিং", "accept", now - 60_000)))
        .put("taskCount", 9)

    @Test
    fun parsesTheBackendShape() {
        val t = WorkTodayParse.parse(sample())
        assertTrue(t.live)
        assertEquals(4, t.items.size)
        assertEquals(9, t.taskCount)
        assertEquals(3, t.tasks.size)
        // ডাক্তার = চেম্বার; আজ বসে এমন দুটো চেম্বার (তৃতীয়টা আজ বসে না)
        val d = t.items[0]
        assertEquals(WorkRoles.CHAMBER, d.role)
        assertEquals(listOf("open", "paused"), d.units.map { it.status })
        assertEquals(now + 1_800_000, d.units[1].pausedUntil)
        assertEquals(listOf(0, 1), d.units.map { it.chamberIndex })
        assertEquals("পপুলার", d.units[0].name)
        assertNull(d.units[0].doctorUid)
        // ফার্মেসি নিজেই এক সারি; খালি শিরোনামের কাজ বাদ
        val ph = t.items[1]
        assertEquals("paused", ph.units.single().status)
        assertEquals(now + 1_800_000, ph.units.single().pausedUntil)
        assertEquals(1200.5, ph.counts["salesTodayTk"]!!, 0.001)
        assertEquals(2, ph.tasks.size)
        assertEquals(listOf("approved", "hours"), ph.setup.map { it.key })
        // ব্লাড ব্যাংক: অবস্থা নেই, স্টক কবের
        assertNull(t.items[2].units.single().status)
        assertEquals(now - 3_600_000, t.items[2].units.single().stockUpdatedAt)
        // অ্যাম্বুলেন্স: যাচাই চলা গাড়ি বাদ, খালি = open
        assertEquals(listOf("a1" to "open", "a2" to "closed"), t.items[3].units.map { it.id to it.status })
    }

    @Test
    fun assistantSendsTheDoctorsUid() {
        val o = JSONObject().put("kind", "doctor").put("id", "doc9").put("name", "ডা. নাসরিন").put("role", "assistant")
            .put("chambers", JSONArray().put(JSONObject().put("chamberIndex", 0).put("name", "X").put("sitsToday", true).put("status", "open")))
        val b = WorkTodayParse.biz(o)
        assertEquals("assistant", b.access)
        assertEquals("doc9", b.units.single().doctorUid)
    }

    @Test
    fun missingOrOtherKeysAreEmptyNotACrash() {
        assertTrue(WorkTodayParse.parse(JSONObject()).items.isEmpty())
        val t = WorkTodayParse.parse(JSONArray().put(JSONObject().put("kind", "lab").put("id", "l1")).put(JSONObject().put("id", "nokind")))
        assertEquals(1, t.items.size)
        assertTrue(t.items[0].counts.isEmpty())
        // অবস্থা না এলে সারি নেই (বোতাম ছাড়া কিছু বাছা দেখায় না)
        assertTrue(t.items[0].units.isEmpty())
        // যাচাই চলা ল্যাব: অবস্থা থাকলেও সারি নয়
        assertTrue(WorkTodayParse.biz(JSONObject().put("kind", "lab").put("id", "l").put("listingStatus", "pending").put("status", "open")).units.isEmpty())
    }

    @Test
    fun switchesOffHideTheirNumbersAndTasks() {
        val t = WorkTodayParse.parse(sample())
        // সব চালু: রসিদ আর পেমেন্টের কাজ থাকে
        assertTrue("invoicesToday" in WorkGate.filter(t.items[1]).counts)
        assertEquals(2, WorkGate.filter(t.items[1]).tasks.size)
        // সব বন্ধ: শুধু সুইচের উপর নির্ভর করা গুলো যায়, বাকিগুলো থাকে
        Features.set(emptyMap(), 2L, null)
        val off = WorkGate.filter(t)
        assertFalse("invoicesToday" in off.items[1].counts)
        assertFalse("onlineToday" in off.items[0].counts)
        assertTrue("new" in off.items[1].counts)
        assertTrue("today" in off.items[0].counts)
        assertEquals(listOf("o1"), off.items[1].tasks.map { it.id })
        assertEquals(listOf("o1", "a1"), off.tasks.map { it.id })
        // merge-এও একই
        val merged = WorkFallback.merge(t, WorkToday(emptyList(), false))
        assertFalse("invoicesToday" in merged.items[1].counts)
        assertEquals(listOf("o1", "a1"), merged.tasks.map { it.id })
    }

    @Test
    fun onlineAndChatKeysFollowTheirSwitch() {
        Features.set(mapOf(Features.ONLINE_CHAMBER to false, Features.CHAT to true), 3L, null)
        assertFalse(WorkGate.allows(WorkRoles.CHAMBER, "onlineToday"))
        assertTrue(WorkGate.allows(WorkRoles.CHAMBER, "chatUnread"))
        assertTrue(WorkGate.allows(WorkRoles.CHAMBER, "today"))
        assertEquals(InvoiceFeatures.key("chamber"), WorkGate.switchFor(WorkRoles.CHAMBER, "receipts"))
    }

    private fun pharmacy(status: String = "approved", closedUntil: Long = 0, openNow: Boolean = true) = Pharmacy(
        "p1", "ডেমো লাইফ ফার্মেসি", "01700000021", "শিববাড়ি মোড়", "গাজীপুর", null, "09:00", "22:00", false, openNow, true, "", 10, 4.5, 12,
        status = status, closedUntil = closedUntil, extras = ListingExtras(openNow = openNow),
    )

    private fun ambulance(id: String, available: Boolean, status: String = "approved") =
        Ambulance(id, "অ্যাম্বুলেন্স $id", "0170000001", listOf("ac"), "গাজীপুর", null, "", "", available, 4.0, 2, status = status)

    @Test
    fun fallbackFromOwnRecords() {
        val s = WorkSummary(
            ambulances = listOf(ambulance("a1", true), ambulance("a2", false), ambulance("a3", true, "pending")),
            pharmacy = pharmacy(closedUntil = now + 30 * 60_000L),
            professional = "nurse",
        )
        val t = WorkFallback.build(s, null, now)
        assertFalse(t.live)
        val ph = t.items.single { it.role == "pharmacy" }.units.single()
        assertEquals("paused", ph.status)
        assertEquals(now + 30 * 60_000L, ph.pausedUntil)
        // যাচাই চলা গাড়ি নয়; খালি = open
        assertEquals(listOf("open", "closed"), t.items.single { it.role == "ambulance" }.units.map { it.status })
        // নার্স: চেম্বারের ঘর আছে কিন্তু অবস্থার সারি নেই; সংখ্যাও নেই
        assertEquals("homecare", t.items.single { it.role == WorkRoles.CHAMBER }.kind)
        assertTrue(t.items.single { it.role == WorkRoles.CHAMBER }.units.isEmpty())
        assertTrue(t.items.all { it.counts.isEmpty() && it.tasks.isEmpty() })
        // কয়েক দিনের বন্ধ = বন্ধ; নিয়মে বন্ধ = বন্ধ; যাচাই চলছে = সারি নেই, ঘর "যাচাই চলছে"
        assertEquals("closed", WorkFallback.build(WorkSummary(pharmacy = pharmacy(closedUntil = now + 3 * 86_400_000L)), null, now).items.single().units.single().status)
        assertEquals("closed", WorkFallback.build(WorkSummary(pharmacy = pharmacy(openNow = false)), null, now).items.single().units.single().status)
        val pending = WorkFallback.build(WorkSummary(pharmacy = pharmacy(status = "pending")), null, now)
        assertTrue(pending.items.single().units.isEmpty())
        assertTrue(workBlocks(pending, listOf("pharmacy")).single().pending)
    }

    @Test
    fun liveKeepsRolesTheServerDidNotSend() {
        val s = WorkSummary(ambulances = listOf(ambulance("a1", true)), pharmacy = pharmacy())
        val merged = WorkFallback.merge(WorkToday(listOf(WorkBiz("pharmacy", "p1", "x", units = listOf(WorkUnit("pharmacy", "p1", "x", "open")))), true), WorkFallback.build(s, null, now))
        assertTrue(merged.live)
        assertEquals(listOf("pharmacy", "ambulance"), merged.items.map { it.role })
        assertEquals(WorkFallback.build(s, null, now), WorkFallback.merge(null, WorkFallback.build(s, null, now)))
    }

    @Test
    fun tilesAndSetup() {
        val t = WorkTodayParse.parse(sample())
        val blocks = workBlocks(t, listOf(WorkRoles.CHAMBER, "pharmacy", "bloodbank", "ambulance"))
        // জানা চাবিগুলোই (salesToday-র সংখ্যা, invoicesToday নয়), সর্বোচ্চ ৪টা
        assertEquals(listOf("new", "active", "salesTodayTk", "finds"), blocks[1].tiles().map { it.first })
        assertEquals(listOf("today", "waiting", "requests"), blocks[0].tiles().map { it.first })
        assertEquals(listOf("calls", "trips"), blocks[3].tiles().map { it.first })
        assertEquals(listOf("approved", "hours"), blocks[1].setupLeft().map { it.key })
        // সব হয়ে গেলে তালিকা লুকানো
        assertTrue(WorkBlock("lab", listOf(WorkBiz("lab", "l", "", setup = listOf(WorkSetup("photo", true))))).setupLeft().isEmpty())
        // দুটো ব্লাড ব্যাংক: সংখ্যা যোগ হয়
        val two = WorkBlock("ambulance", listOf(WorkBiz("ambulance", "a", "", counts = mapOf("calls" to 2.0)), WorkBiz("ambulance", "b", "", counts = mapOf("calls" to 3.0))))
        assertEquals(listOf("calls" to 5.0), two.tiles())
    }

    @Test
    fun nowListUsesServerOrderAndTotal() {
        val t = WorkTodayParse.parse(sample())
        val all = workBlocks(t, WorkRoles.owned(WorkSummary(), t))
        val l = workTasks(t, all, allRoles = true)
        // "সব": সার্ভারের ক্রম, মোট taskCount
        assertEquals(listOf("o2", "o1", "a1"), l.top.map { it.id })
        assertEquals(9, l.total)
        // পুরো তালিকায় একই কাজ দুবার নয়
        assertEquals(l.all.map { it.key }.distinct(), l.all.map { it.key })
        // একটা ভূমিকা: সেই ব্যবসার কাজ
        val ph = workTasks(t, workBlocks(t, listOf("pharmacy")), allRoles = false)
        assertEquals(listOf("o1", "o2"), ph.top.map { it.id })
        assertEquals(2, ph.total)
        // সুইচ বন্ধ হলে "সব"-এর তালিকা থেকেও যায়
        Features.set(emptyMap(), 2L, null)
        assertEquals(listOf("o1", "a1"), workTasks(t, all, true).top.map { it.id })
    }

    @Test
    fun targetsOpenTheRightPart() {
        assertEquals("sales", WorkTargets.forCount("pharmacy", "salesTodayTk"))
        assertEquals("orders:active", WorkTargets.forCount("pharmacy", "active"))
        assertEquals("orders:new", WorkTargets.forCount("pharmacy", "new"))
        assertEquals("finds", WorkTargets.forCount("pharmacy", "finds"))
        assertEquals("orders:active", WorkTargets.forCount("lab", "active"))
        assertEquals("orders:reported", WorkTargets.forCount("lab", "reported"))
        assertNull(WorkTargets.forCount("ambulance", "calls"))
        assertEquals("orders:new", WorkTargets.forTask(WorkTask("pharmacy", "o", "t", action = "accept", type = "order_new")))
        assertEquals("finds", WorkTargets.forTask(WorkTask("pharmacy", "f", "t", action = "answer", type = "find")))
        assertEquals("orders:active", WorkTargets.forTask(WorkTask("lab", "o", "t", action = "report", type = "lab_report")))
        assertEquals("orders:new", WorkTargets.forTask(WorkTask("lab", "o", "t", action = "receive", type = "lab_new")))
    }

    @Test
    fun pauseRules() {
        // ঢাকার মাঝরাত: ১০:০০ থেকে ১৪ ঘণ্টা পর
        assertEquals(now + 14 * 3_600_000L, WorkStatusApi.endOfDay(now))
        // পুরোনো সার্ভারে বিরতি নেই; নতুনে ফার্মেসি/ল্যাব/চেম্বার, অ্যাম্বুলেন্সে কখনো নয়
        assertFalse(WorkStatusApi.canPause("pharmacy", live = false))
        assertTrue(WorkStatusApi.canPause("pharmacy", live = true))
        assertTrue(WorkStatusApi.canPause("lab", live = true))
        assertTrue(WorkStatusApi.canPause(WorkRoles.CHAMBER, live = true))
        assertFalse(WorkStatusApi.canPause("ambulance", live = true))
        assertFalse(WorkStatusApi.canPause("bloodbank", live = true))
    }

    @Test
    fun banglaNumbersAndWords() {
        assertEquals("৳১,২০০.৫", WorkText.countValue("salesTodayTk", 1200.5))
        assertEquals("১২", WorkText.countValue("new", 12.0))
        assertEquals("৭", WorkText.countValue("salesToday", 7.0))
        assertEquals("নতুন অর্ডার", WorkText.count("pharmacy", "new"))
        assertNull(WorkText.count("pharmacy", "salesToday"))
        assertEquals("গ্রহণ করুন", WorkText.action("accept"))
        assertEquals("ডাকুন", WorkText.action("next"))
        assertEquals("নমুনা নিয়েছি", WorkText.action("receive"))
        assertEquals("উত্তর দিন", WorkText.action("answer"))
        assertEquals("খুলুন", WorkText.action("something"))
        assertEquals("ছবি", WorkText.setup("photo"))
        assertEquals("পরীক্ষার দাম", WorkText.setup("prices"))
        AppPrefs.lang.value = Lang.EN
        assertEquals(tr("New orders", "নতুন অর্ডার"), WorkText.count("pharmacy", "new"))
        assertTrue(WorkText.count("pharmacy", "new")!!.startsWith("New"))
        assertEquals("৳1,200.5", WorkText.countValue("salesTodayTk", 1200.5))
    }

    @Test
    fun ownedRolesFollowChipOrder() {
        val s = WorkSummary(ambulances = listOf(ambulance("a1", true)), pharmacy = pharmacy(), professional = "doctor")
        assertEquals(listOf(WorkRoles.CHAMBER, "pharmacy", "ambulance"), WorkRoles.owned(s, null))
        // সার্ভার আরেকটা ভূমিকা জানালে (যেমন সহকারী, ল্যাব) সেটাও
        assertEquals(listOf(WorkRoles.CHAMBER, "pharmacy", "lab", "ambulance"), WorkRoles.owned(s, WorkToday(emptyList(), roles = listOf("lab"))))
        assertEquals(listOf(WorkRoles.CHAMBER), WorkRoles.owned(WorkSummary(), WorkToday(emptyList(), roles = listOf("doctor"))))
    }
}
