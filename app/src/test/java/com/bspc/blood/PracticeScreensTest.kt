package com.bspc.blood

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.google.firebase.FirebaseApp
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * চিকিৎসা পদ্ধতি আর নতুন পেশাজীবী (Practice.kt): সুইচ বন্ধ = আগের মতো (সবাই এলোপ্যাথি, পদ্ধতি/নতুন পেশা নেই);
 * চালু = নামের পাশে পদ্ধতি, খোঁজার চিপ, নতুন পেশার আবেদন, প্রেসক্রিপশনের নিয়ম (হাতে লেখা ওষুধ, ফার্মেসিতে পাঠানো নেই,
 * ফিজিও "পরামর্শ ও ব্যায়াম", সাইকোলজিস্টের সেশনের নোট + সাইকিয়াট্রিস্টের কথা), সাইকোলজিস্টের গোপনীয়তা।
 * ছবি app/build/screens-এ।
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi")
class PracticeScreensTest {
    @get:Rule
    val rule = createComposeRule()

    private val allOn = mapOf(Practice.SYSTEMS to true, Practice.PHYSIO to true, Practice.PSYCH to true)
    private val allOff = mapOf(Practice.SYSTEMS to false, Practice.PHYSIO to false, Practice.PSYCH to false)

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        if (FirebaseApp.getApps(ctx).isEmpty()) runCatching { FirebaseApp.initializeApp(ctx) }
        Areas.ensure(ctx)
        AppPrefs.lang.value = Lang.BN
        AppPrefs.theme.value = ThemeMode.LIGHT
        LoginGate.uid.value = "demo"
        LoginGate.profileReady.value = true
        CareMode.asUid.value = null
        Practice.testKeys = allOn
    }

    @After
    fun tearDown() {
        Practice.testKeys = null
        CareMode.asUid.value = null
    }

    private fun show(content: @Composable () -> Unit) {
        rule.setContent {
            GetBloodTheme { Surface { Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() } } }
        }
        rule.waitForIdle()
    }

    private fun shot(name: String) {
        rule.waitForIdle()
        captureScreenRoboImage("build/screens/$name.png")
    }

    private fun rxJson(type: String, system: String, meds: List<RxMed> = emptyList(), complaints: String = "", advice: String = "", tests: List<String> = emptyList()) =
        JSONObject()
            .put("id", "rx-$type-$system").put("code", "ABCD2345EF").put("status", "active").put("createdAt", 1_792_886_400_000L).put("doctorUid", "d1")
            .put(
                "doctor",
                JSONObject().put("name", "ডেমো $type").put("degrees", "DHMS").put("regNo", "H-778").put("specialties", JSONArray())
                    .put("system", system).put("type", type)
                    .put("chamber", JSONObject().put("name", "ডেমো সেন্টার").put("address", "গাজীপুর")),
            )
            .put("patient", JSONObject().put("name", "ডেমো রোগী রহিম").put("age", 34).put("gender", "male"))
            .put("complaints", complaints).put("advice", advice)
            .put("medicines", JSONArray(meds.map { it.toJson() }))
            .put("tests", JSONArray(tests))

    // ---------------- সুইচ বন্ধ: আগের মতো ----------------

    @Test
    fun switchesOffLooksAsToday() {
        Practice.testKeys = allOff
        // সবাই এলোপ্যাথি, লেবেল নেই
        assertEquals(Practice.ALLOPATHY, Practice.system("homeopathy"))
        assertFalse(Practice.nonAllopathic("unani"))
        assertNull(Practice.nameTag("doctor", "homeopathy"))
        // নতুন পেশা নেই; ধরনের তালিকা আগের মতো
        assertTrue(Practice.newTypes().isEmpty())
        assertEquals(Badges.ALL, Practice.badgeTypes())
        assertEquals(PROVIDER_TYPES, Practice.providerTypes())
        assertEquals(CHAMBER_TYPES, Practice.chamberTypes())
        assertEquals(HOME_PROVIDER_TYPES, Practice.homeTypes())
        // প্রেসক্রিপশন: হোমিওপ্যাথ লেখা এলেও সুইচ বন্ধে আগের নিয়ম (তালিকা, ফার্মেসি)
        val rx = Rx.parse(rxJson("doctor", "homeopathy", listOf(RxMed("Napa", dose = "1+0+1", days = 3))))
        assertEquals(Practice.RxMode.FULL, Practice.rxMode(rx))
        assertTrue(Practice.pharmacyOk(rx))
        assertNull(Practice.rxHeaderTag(rx))
        assertEquals("BMDC H-778", Practice.regLine("doctor", "homeopathy", "H-778"))
        // বিশেষজ্ঞতা আগের তালিকা
        assertEquals(Specialties.of("doctor"), Specialties.of("doctor", "homeopathy"))
        // ব্যাজের কাগজ আগের মতো (BMDC, ঐচ্ছিক)
        assertFalse(VerifyDocs.badge("doctor", Practice.system("homeopathy")).first().required)
    }

    @Test
    fun switchesOffBadgeDialog() {
        Practice.testKeys = allOff
        show { BadgeApplyDialog(onDismiss = {}, onApplied = {}) }
        rule.onNodeWithTag("system_picker", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithText(Practice.proLabel(Practice.PHYSIO_TYPE)!!).assertDoesNotExist()
        rule.onNodeWithText(Practice.proLabel(Practice.PSYCH_TYPE)!!).assertDoesNotExist()
        rule.onNodeWithText("ডাক্তার").assertExists()
        shot("practice_off_badge_360_bn")
    }

    @Test
    fun switchesOffProviderCard() {
        Practice.testKeys = allOff
        show { ProviderCard(provider("homeopathy"), CareFilter()) {} }
        // মূল লাইনে পদ্ধতি নেই (সুইচ বন্ধ)
        assertTrue(rule.onAllNodesWithText(Practice.systemLabel("homeopathy"), substring = true).fetchSemanticsNodes().isEmpty())
        // খোঁজার ফিল্টারে পদ্ধতি যায় না
        assertEquals("হোমিওপ্যাথি", Practice.systemLabel("homeopathy"))
    }

    // ---------------- চালু: পদ্ধতি ----------------

    @Test
    fun systemsRules() {
        assertEquals("homeopathy", Practice.system("homeopathy"))
        assertEquals(Practice.ALLOPATHY, Practice.system(""))
        assertEquals(Practice.ALLOPATHY, Practice.system("magic"))
        assertEquals("এলোপ্যাথি", Practice.systemLabel("allopathy"))
        assertEquals("ইউনানি", Practice.systemLabel("unani"))
        assertEquals("বাংলাদেশ হোমিওপ্যাথিক বোর্ড", Practice.body("homeopathy"))
        assertEquals(Practice.body("unani"), Practice.body("ayurveda"))
        assertEquals("BMDC", Practice.body("allopathy"))
        assertEquals("DHMS / BHMS", Practice.degreeHint("homeopathy"))
        assertEquals("DUMS / BUMS", Practice.degreeHint("unani"))
        assertEquals("DAMS / BAMS", Practice.degreeHint("ayurveda"))
        assertEquals("MBBS / BDS", Practice.degreeHint("allopathy"))
        // এলোপ্যাথিরও লেবেল (সব ডাক্তারের পাশে); অন্য পেশার নয়
        assertEquals("এলোপ্যাথি", Practice.nameTag("doctor", ""))
        assertNull(Practice.nameTag("nurse", "homeopathy"))
        // নিবন্ধনের লাইন: বোর্ডের নাম, "BMDC" নয়
        assertEquals("বাংলাদেশ হোমিওপ্যাথিক বোর্ড H-1", Practice.regLine("doctor", "homeopathy", "H-1"))
        assertEquals("BMDC A-1", Practice.regLine("doctor", "allopathy", "A-1"))
        assertEquals("নিবন্ধন: P-9", Practice.regLine(Practice.PHYSIO_TYPE, "", "P-9"))
        assertEquals("", Practice.regLine("doctor", "unani", ""))
        // বিশেষজ্ঞতা: পদ্ধতির ছোট তালিকা আর "সাধারণ"
        val alt = Specialties.of("doctor", "ayurveda")
        assertTrue("alt_general" in alt)
        assertTrue("alt_skin_hair" in alt)
        assertEquals("সাধারণ", Specialties.label("alt_general"))
        assertFalse("cardiology" in alt)
        assertTrue("cardiology" in Specialties.of("doctor", "allopathy"))
        // অন্য পদ্ধতির ব্যাজে বোর্ডের সনদ লাগে
        assertTrue(VerifyDocs.badge("doctor", "unani").first().required)
        assertFalse(VerifyDocs.badge("doctor", "allopathy").first().required)
    }

    @Test
    fun providerParseAndFilter() {
        // সার্ভারের system ঘর পড়া; না থাকলে এলোপ্যাথি
        val f = CareFilter(system = "unani")
        assertEquals("unani", f.system)
        assertEquals(CareFilter().copy(system = null), CareFilter())
        val p = provider("unani")
        assertEquals("ইউনানি", Practice.nameTag(p.type, p.system))
        assertEquals("এলোপ্যাথি", Practice.nameTag("doctor", provider("").system))
    }

    @Test
    fun systemChipsCounts() {
        show { SystemChips(null, mapOf("homeopathy" to 3, "allopathy" to 12)) {} }
        rule.onNodeWithText(Practice.systemLabel("homeopathy") + " (" + num(3) + ")").assertExists()
        rule.onNodeWithText(Practice.systemLabel("allopathy") + " (" + num(12) + ")").assertExists()
    }

    @Test
    fun providerCardWithSystem() {
        show {
            ProviderCard(provider("homeopathy"), CareFilter()) {}
            ProviderCard(provider("allopathy").copy(uid = "p2", name = "ডা. ডেমো হাসান", degrees = "MBBS"), CareFilter()) {}
        }
        // পদ্ধতি এখন কার্ডের মূল লাইনে (বিশেষজ্ঞতা · পদ্ধতি · ফি)
        assertTrue(rule.onAllNodesWithText(Practice.systemLabel("homeopathy"), substring = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue(rule.onAllNodesWithText(Practice.systemLabel("allopathy"), substring = true).fetchSemanticsNodes().isNotEmpty())
        // "ডা." নিজে বসে না — নাম যেমন লেখা
        rule.onNodeWithText("ডেমো হোমিও চিকিৎসক").assertExists()
        rule.onNodeWithText("ডা. ডেমো হোমিও চিকিৎসক").assertDoesNotExist()
        shot("practice_provider_cards_360_bn")
    }

    @Test
    fun systemChipsRow() {
        var picked: String? = "x"
        show { SystemChips(null) { picked = it } }
        rule.onNodeWithText("সব").assertExists()
        Practice.SYSTEM_LIST.forEach { rule.onNodeWithText(Practice.systemLabel(it)).assertExists() }
        rule.onNodeWithText(Practice.systemLabel("unani")).performClickSafe()
        assertEquals("unani", picked)
        shot("practice_system_chips_360_bn")
    }

    @Test
    fun badgeDialogWithSystemsAndNewTypes() {
        show { BadgeApplyDialog(onDismiss = {}, onApplied = {}) }
        // ভুল হলে কোন লাইনে তা CI-র সারাংশে আসে (IllegalStateException)
        runCatching { rule.onNodeWithTag("system_picker", useUnmergedTree = true).assertExists() }.onFailure { throw IllegalStateException("picker: " + it.message) }
        runCatching { rule.onAllNodesWithText(Practice.proLabel(Practice.PHYSIO_TYPE)!!, substring = true).fetchSemanticsNodes().isNotEmpty().let { ok -> check(ok) } }
            .onFailure { throw IllegalStateException("physio chip: " + it.message) }
        runCatching { rule.onAllNodesWithText(Practice.proLabel(Practice.PSYCH_TYPE)!!, substring = true).fetchSemanticsNodes().isNotEmpty().let { ok -> check(ok) } }
            .onFailure { throw IllegalStateException("psych chip: " + it.message) }
        // শুরুতে এলোপ্যাথি: BMDC
        runCatching { rule.onNodeWithText("নিবন্ধন: BMDC · ডিগ্রি: MBBS / BDS").assertExists() }.onFailure { throw IllegalStateException("bmdc note: " + it.message) }
        shot("practice_badge_doctor_360_bn")
    }

    @Test
    fun badgeDialogHomeopathy() {
        // আগের আবেদন বাতিল হয়েছিল (হোমিওপ্যাথি) — আবার আবেদন: পদ্ধতি আর বোর্ড আগে থেকে
        show { BadgeApplyDialog(onDismiss = {}, onApplied = {}, current = BadgeState("doctor", "rejected", system = "homeopathy")) }
        rule.onNodeWithText("নিবন্ধন: বাংলাদেশ হোমিওপ্যাথিক বোর্ড · ডিগ্রি: DHMS / BHMS").assertExists()
        // বোর্ডের সনদ লাগে
        rule.onNodeWithText(VerifyDocs.badge("doctor", "homeopathy").first().label, substring = true).assertExists()
        shot("practice_badge_homeopathy_360_bn")
    }

    @Test
    fun badgeDialogPsychologist() {
        // সাইকোলজিস্ট: পদ্ধতির ঘর নেই, ডিগ্রি আর সোসাইটির কথা
        show { BadgeApplyDialog(onDismiss = {}, onApplied = {}, current = BadgeState(Practice.PSYCH_TYPE, "rejected")) }
        rule.onNodeWithTag("system_picker", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithText(Practice.regHint(Practice.PSYCH_TYPE, "")!!).assertExists()
        shot("practice_badge_psychologist_360_bn")
    }

    // ---------------- নতুন পেশা ----------------

    @Test
    fun newTypesRules() {
        assertEquals(listOf(Practice.PHYSIO_TYPE, Practice.PSYCH_TYPE), Practice.newTypes())
        assertEquals(Badges.ALL.filter { it != "expert" } + Practice.PHYSIO_TYPE + Practice.PSYCH_TYPE + "expert", Practice.badgeTypes())
        assertTrue(Practice.PHYSIO_TYPE in Practice.chamberTypes())
        assertTrue(Practice.PSYCH_TYPE in Practice.chamberTypes())
        // বাসায় সেবা: ফিজিও হ্যাঁ, সাইকোলজিস্ট না
        assertTrue(Practice.PHYSIO_TYPE in Practice.homeTypes())
        assertFalse(Practice.PSYCH_TYPE in Practice.homeTypes())
        assertTrue(Practice.onlineCapable(Practice.PSYCH_TYPE))
        assertFalse(Practice.onlineCapable("nutritionist"))
        assertEquals("ফিজিওথেরাপিস্ট", Badges.label(Practice.PHYSIO_TYPE))
        assertEquals("সাইকোলজিস্ট / কাউন্সেলর", Badges.label(Practice.PSYCH_TYPE))
        assertEquals(Practice.PHYSIO_SPECIALTIES, Specialties.of(Practice.PHYSIO_TYPE))
        assertEquals(Practice.PSYCH_SPECIALTIES, Specialties.of(Practice.PSYCH_TYPE))
        assertEquals("ট্রমা", Specialties.label("psy_trauma"))
        assertEquals(Practice.PHYSIO_HOME, HomeServices.of(Practice.PHYSIO_TYPE))
        assertEquals("বাসায় ব্যায়াম থেরাপি", HomeServices.label("physio_home_exercise"))
        // নোটিফিকেশন থেকে নিজের চেম্বার
        assertTrue(VerifyDocs.badge(Practice.PSYCH_TYPE).first().required)
        assertFalse(VerifyDocs.badge(Practice.PSYCH_TYPE)[1].required)
    }

    // ---------------- প্রেসক্রিপশন ----------------

    @Test
    fun rxRulesFromServer() {
        // PRACTICE.md-এর সারণি
        fun r(m: Boolean, f: Boolean, d: Boolean, ph: Boolean, t: Boolean, sec: Any?, n: Any?) = Practice.RxRules.parse(
            JSONObject().put("medicines", m).put("freeText", f).put("drugCatalog", d).put("interactions", d).put("pharmacy", ph).put("tests", t)
                .put("section", sec ?: JSONObject.NULL).put("notice", n ?: JSONObject.NULL),
        )!!
        assertEquals(Practice.RxMode.FULL, r(true, false, true, true, true, null, null).mode)
        assertEquals(Practice.RxMode.FREE, r(true, true, false, false, true, null, null).mode)
        val physio = r(false, false, false, false, false, "পরামর্শ ও ব্যায়াম", null)
        assertEquals(Practice.RxMode.PHYSIO, physio.mode)
        assertNull(physio.notice)
        val psy = r(false, false, false, false, false, "সেশন নোট ও পরামর্শ", "ওষুধের প্রয়োজনে সাইকিয়াট্রিস্ট (এলোপ্যাথি ডাক্তার) দেখান")
        assertEquals(Practice.RxMode.PSYCH, psy.mode)
        assertEquals(Practice.psychiatristNote(), psy.notice)
        // প্রেসক্রিপশনের rxRules প্রথমে
        val rx = Rx.parse(rxJson("doctor", "").put("rxRules", JSONObject().put("medicines", true).put("freeText", true).put("drugCatalog", false).put("pharmacy", false).put("tests", true)))
        assertEquals(Practice.RxMode.FREE, Practice.rxMode(rx))
        assertFalse(Practice.pharmacyOk(rx))
        assertEquals(Practice.RxMode.PSYCH, Practice.RxRules.of(Practice.RxMode.PSYCH).mode)
        assertEquals(Practice.RxMode.FREE, Practice.RxRules.of(Practice.RxMode.FREE).mode)
    }

    @Test
    fun rxModes() {
        assertEquals(Practice.RxMode.FULL, Practice.rxMode("doctor", "allopathy"))
        assertEquals(Practice.RxMode.FREE, Practice.rxMode("doctor", "unani"))
        assertEquals(Practice.RxMode.PHYSIO, Practice.rxMode(Practice.PHYSIO_TYPE, ""))
        assertEquals(Practice.RxMode.PSYCH, Practice.rxMode(Practice.PSYCH_TYPE, ""))
        val homeo = Rx.parse(rxJson("doctor", "homeopathy", listOf(RxMed("Nux Vomica 30", dose = "৪ ফোঁটা, দিনে ৩ বার"))))
        assertEquals("homeopathy", homeo.system)
        assertEquals("doctor", homeo.doctorType)
        assertFalse(Practice.pharmacyOk(homeo))
        // হাতে লেখা নিয়মে "চলবে" নিজে বসে না
        assertEquals(num("৪ ফোঁটা, দিনে ৩ বার"), RxText.howTo(homeo.medicines[0], Practice.RxMode.FREE))
        assertTrue(RxText.howTo(RxMed("Napa", dose = "1+0+1"), Practice.RxMode.FULL).contains("চলবে"))
        assertTrue(Practice.pharmacyOk(Rx.parse(rxJson("doctor", "allopathy"))))
        // হাতে লেখা লাইনের JSON: ধরন/মাত্রা/আগে-পরে নয়
        val fj = RxMed("Nux Vomica 30", dose = "৪ ফোঁটা", note = "খালি পেটে").toFreeJson()
        assertEquals("Nux Vomica 30", fj.getString("name"))
        assertEquals("৪ ফোঁটা", fj.getString("dose"))
        assertFalse(fj.has("form"))
        assertFalse(fj.has("timing"))
        // পুরনো সার্ভার (system/type নেই) = এলোপ্যাথি
        assertTrue(Practice.pharmacyOk(Rx.parse(rxJson("doctor", "").put("doctor", JSONObject().put("name", "x")))))
        assertEquals("পরামর্শ ও ব্যায়াম", RxText.adviceTitle(Practice.RxMode.PHYSIO))
        assertEquals("সেশন নোট ও পরামর্শ", RxText.adviceTitle(Practice.RxMode.PSYCH))
        // সার্ভারের freeText/pharmacy (rx view) মানা হয় — সুইচ বন্ধ হলেও হাতে লেখা লাইন
        Practice.testKeys = allOff
        val legacy = Rx.parse(rxJson("doctor", "unani").put("freeText", true).put("pharmacy", false))
        assertEquals(Practice.RxMode.FREE, Practice.rxMode(legacy))
        assertFalse(Practice.pharmacyOk(legacy))
        Practice.testKeys = allOn
        assertEquals("ওষুধের প্রয়োজনে সাইকিয়াট্রিস্ট (এলোপ্যাথি ডাক্তার) দেখান", Practice.psychiatristNote())
    }

    @Test
    fun rxPaperHomeopathy() {
        val rx = Rx.parse(
            rxJson(
                "doctor", "homeopathy",
                listOf(RxMed("Nux Vomica 30", dose = "৪ ফোঁটা, দিনে ৩ বার"), RxMed("Arnica 200", dose = "সপ্তাহে ১ বার", note = "খালি পেটে")),
                complaints = "পেটে গ্যাস, অরুচি", advice = "তেল-মসলা কম", tests = listOf("CBC"),
            ),
        )
        show { RxPaper(rx) }
        rule.onNodeWithText(Practice.systemLabel("homeopathy")).assertExists()
        rule.onNodeWithText("বাংলাদেশ হোমিওপ্যাথিক বোর্ড H-778").assertExists()
        rule.onAllNodesWithText("BMDC", substring = true).assertCountEquals(0)
        rule.onNodeWithText("Nux Vomica 30").assertExists()
        rule.onNodeWithText("চলবে").assertDoesNotExist()
        // পরীক্ষা সব পদ্ধতিতে
        rule.onNodeWithText("• CBC").assertExists()
        shot("practice_rx_homeopathy_360_bn")
    }

    @Test
    fun rxPaperPhysio() {
        val rx = Rx.parse(rxJson(Practice.PHYSIO_TYPE, "", complaints = "কোমরে ব্যথা", advice = "কোমরের ব্যায়াম — ১০ বার, দিনে ২ বার"))
        show { RxPaper(rx) }
        rule.onNodeWithText("পরামর্শ ও ব্যায়াম").assertExists()
        rule.onNodeWithText("ফিজিওথেরাপিস্ট").assertExists()
        rule.onNodeWithText("℞").assertDoesNotExist()
        shot("practice_rx_physio_360_bn")
    }

    @Test
    fun rxPaperPsychologist() {
        val rx = Rx.parse(rxJson(Practice.PSYCH_TYPE, "", advice = "ঘুম কম, দুশ্চিন্তা। প্রতিদিন ১০ মিনিট শ্বাসের ব্যায়াম"))
        show { RxPaper(rx) }
        rule.onNodeWithText("সেশন নোট ও পরামর্শ").assertExists()
        rule.onNodeWithText(Practice.psychiatristNote()).assertExists()
        rule.onNodeWithText("℞").assertDoesNotExist()
        shot("practice_rx_psychologist_360_bn")
    }

    @Test
    fun rxPaperSwitchOffNoLabel() {
        Practice.testKeys = allOff
        val rx = Rx.parse(rxJson("doctor", "homeopathy", listOf(RxMed("Napa", "Tablet", "Paracetamol 500 mg", "1+0+1", "after", 5))))
        show { RxPaper(rx) }
        rule.onNodeWithText(Practice.systemLabel("homeopathy")).assertDoesNotExist()
        rule.onNodeWithText("BMDC H-778").assertExists()
        rule.onNodeWithText("℞").assertExists()
    }

    // ---------------- সাইকোলজিস্টের গোপনীয়তা ----------------

    @Test
    fun psychShareRow() {
        // কেয়ারগিভারপ্রতি সুইচ (care_share_psych) — শুরুতে বন্ধ
        val links = listOf(
            CareLink("me", "c1", "", "ডেমো কেয়ারগিভার করিম", "karim@example.com", "active", 0L),
            CareLink("me", "c2", "", "ডেমো কেয়ারগিভার সালমা", "salma@example.com", "active", 0L, sharePsych = true),
        )
        show { PsychShareRow(initial = links) }
        rule.onNodeWithText("কেয়ারগিভারের সাথে শেয়ার").assertExists()
        rule.onNodeWithText("শেয়ার করা: ডেমো কেয়ারগিভার সালমা").assertExists()
        shot("practice_psych_share_360_bn")
        rule.onNodeWithTag("psych_share").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("psych_share_c1").assertIsOff()
        rule.onNodeWithTag("psych_share_c2").assertIsOn()
        shot("practice_psych_share_dialog_360_bn")
    }

    @Test
    fun psychShareNoCaregiver() {
        show { PsychShareRow(initial = emptyList()) }
        rule.onNodeWithText("গোপন — আপনার কোনো কেয়ারগিভার নেই").assertExists()
    }

    @Test
    fun careLinkParse() {
        // care_list-এর sharePsych (না থাকলে বন্ধ)
        assertFalse(CareLink("p", "c", "", "", "", "active", 0L).sharePsych)
        assertEquals("care_share_psych", PracticeWire.SHARE_ACTION)
    }

    /** title_guard চালু: "আমার কাজ"-এর ব্যবসার কার্ডে হোমিওপ্যাথের চেম্বারের সারি "হোমিওপ্যাথিক চিকিৎসক · …", "ডাক্তার" নয়; বন্ধে আগের মতো */
    @Test
    fun workTabHomeopathTitleGuard() {
        val homeo = WorkSummary(professional = "doctor", proSystem = "homeopathy", proTypeLabel = "হোমিওপ্যাথিক চিকিৎসক", proTypeLabelEn = "Homeopathic practitioner")
        Practice.testKeys = allOn + (TitleGuard.KEY to true)
        MyWork.summary.value = homeo
        rule.setContent { GetBloodTheme { Surface { WorkBusinessList(WorkRoles.ORDER.toSet(), {}, {}) } } }
        rule.waitForIdle()
        rule.onNodeWithText("হোমিওপ্যাথিক চিকিৎসক · বুকিং, রোগী, সেটআপ").assertExists()
        rule.onAllNodesWithText("ডাক্তার · বুকিং, রোগী, সেটআপ").assertCountEquals(0)
        shot("work_tab_homeopath_title_guard_bn")
        // সুইচ বন্ধ: সার্ভার "ডাক্তার" পাঠায়
        Practice.testKeys = allOn + (TitleGuard.KEY to false)
        rule.runOnIdle { MyWork.summary.value = homeo.copy(proTypeLabel = "ডাক্তার", proTypeLabelEn = "Doctor") }
        rule.waitForIdle()
        rule.onNodeWithText("ডাক্তার · বুকিং, রোগী, সেটআপ").assertExists()
        MyWork.clear()
    }

    // ---------------- সহায়ক ----------------

    private fun provider(system: String) = Provider(
        "p1", "doctor", "ডেমো হোমিও চিকিৎসক", listOf("alt_general"), "DHMS", "", true, true,
        listOf(Chamber("h1", "Demo Homeo Centre", "ডেমো হোমিও সেন্টার", "গাজীপুর", null, null, "", "", "300", true, "")),
        null, 0.0, 0, system = system,
    )

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.performClickSafe() {
        runCatching { performScrollTo() }
        performClick()
        rule.waitForIdle()
    }
}
