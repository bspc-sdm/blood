package com.bspc.blood

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.google.firebase.FirebaseApp
import org.junit.Assert.assertEquals
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
 * "ওষুধ খুঁজুন" — কতটা লাগবে (backend/docs/MEDFIND.md), ৩৬০dp, বাংলা আর ইংরেজি। রোগী: ওষুধ বেছে − সংখ্যা + আর ধরন
 * অনুযায়ী একক (ট্যাবলেট: ১ পাতা শুরুতে, পিসও; সিরাপ: বোতল); "আমার খোঁজ"-এ পুরোটা থাকা দোকান আগে, কম থাকলে "আপনার দরকার …"।
 * ফার্মেসি: "৩ পাতা লাগবে", কতটা আছে লিখে পাঠান — "নেই" বোতাম নেই, চুপচাপ "বাদ দিন"; আগের উত্তর বদলানো।
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi")
class MedFindQtyScreensTest {
    @get:Rule
    val rule = createComposeRule()

    private val napa = FindDrug(1, "Napa", "Paracetamol 500 mg", "Tablet", "Beximco Pharmaceuticals Ltd.")
    private val syrup = FindDrug(2, "Napa", "Paracetamol 120 mg/5 ml", "Syrup", "Beximco Pharmaceuticals Ltd.")
    private val now = System.currentTimeMillis()

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        if (FirebaseApp.getApps(ctx).isEmpty()) runCatching { FirebaseApp.initializeApp(ctx) }
        AppPrefs.theme.value = ThemeMode.LIGHT
        AppPrefs.lang.value = Lang.BN
    }

    private fun show(content: @Composable () -> Unit) {
        rule.setContent { GetBloodTheme { Surface { content() } } }
        rule.waitForIdle()
    }

    private fun shot(name: String) {
        rule.waitForIdle()
        captureScreenRoboImage("build/screens/$name.png")
    }

    // ---------------- নিয়ম ----------------

    @Test
    fun unitsTextsAndParsing() {
        assertEquals(listOf("strip", "pcs"), MedQty.unitsFor("Tablet"))
        assertEquals(listOf("strip", "pcs"), MedQty.unitsFor("Soft Gelatin Capsule"))
        assertEquals(listOf("bottle"), MedQty.unitsFor("Powder For Suspension"))
        assertEquals(listOf("vial"), MedQty.unitsFor("IV/IM Injection"))
        assertEquals(listOf("unit"), MedQty.unitsFor("Nebuliser Suspension"))
        assertEquals(listOf("unit"), MedQty.unitsFor("Cream"))
        AppPrefs.lang.value = Lang.BN
        assertEquals("৩ পাতা", MedQty.text(3, "strip"))
        assertEquals("২টি/ভায়াল", MedQty.text(2, "vial"))
        assertEquals("৫টি", MedQty.text(5, "unit"))
        assertEquals("১০ পিস লাগবে", MedQty.need(10, "pcs"))
        assertEquals("", MedQty.need(null, null))
        assertEquals(12, MedQty.parse("১২"))
        assertNull(MedQty.parse("0"))
        assertNull(MedQty.parse("1000"))
        assertEquals(1000, MedQty.parse("1000", MedQty.HAVE_MAX))
        assertNull(MedQty.parse("10000", MedQty.HAVE_MAX))
        assertNull(MedQty.parse("1.5"))
        val f = myFind()
        assertEquals("আছে — ১২ পিস · পুরোটা আছে", MedQty.reply(f, f.have[0]))
        assertEquals("আছে — ৪ পিস (আপনার দরকার ১০)", MedQty.reply(f, f.have[1]))
        AppPrefs.lang.value = Lang.EN
        assertEquals("3 strips", MedQty.text(3, "strip"))
        assertEquals("1 bottle", MedQty.text(1, "bottle"))
        assertEquals("Has 4 pcs (you need 10)", MedQty.reply(f, f.have[1]))
        assertEquals("Has 12 pcs · all you need", MedQty.reply(f, f.have[0]))
    }

    // ---------------- রোগী: কতটা লাগবে ----------------

    private var sentQty = 0
    private var sentUnit = ""

    /** MedFindPanel-এ ওষুধ বাছার পরের কার্ডের মতো */
    private fun picker(d: FindDrug) = show {
        var qty by remember { mutableIntStateOf(1) }
        var unit by remember { mutableStateOf(MedQty.unitsFor(d.form)[0]) }
        sentQty = qty
        sentUnit = unit
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(d.name, style = MaterialTheme.typography.titleMedium)
                    Text(d.detail, style = MaterialTheme.typography.bodySmall)
                    QtyPicker(d.form, qty, unit, { qty = it }, { unit = it })
                    Text(tr("Where", "কোথায়"), style = MaterialTheme.typography.titleSmall)
                    Button(onClick = {}, modifier = Modifier.fillMaxWidth()) { ButtonContent(false, tr("Ask nearby pharmacies", "কাছের দোকানগুলোকে জিজ্ঞেস করুন"), R.drawable.ic_send) }
                }
            }
        }
    }

    private fun assertInside360(tag: String) {
        val w = with(rule.density) { 360.dp.toPx() }
        rule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().forEach { n ->
            assertTrue("$tag inside the screen: ${n.boundsInRoot}", n.boundsInRoot.left >= 0f && n.boundsInRoot.right <= w + 1f)
        }
    }

    @Test
    fun qtyPicker360Bn() {
        picker(napa)
        rule.onNodeWithTag("qty_input").assertTextContains("১")
        rule.onNodeWithTag("qty_unit_strip").assertIsSelected()
        rule.onNodeWithTag("qty_minus").assertIsNotEnabled()
        rule.onNodeWithTag("qty_plus").performClick()
        rule.onNodeWithTag("qty_plus").performClick()
        rule.waitForIdle()
        assertEquals(3, sentQty)
        rule.onNodeWithTag("qty_input").assertTextContains("৩")
        for (t in listOf("qty_minus", "qty_input", "qty_plus", "qty_unit_strip", "qty_unit_pcs")) assertInside360(t)
        assertTrue(rule.onAllNodesWithText("কতটা লাগবে").fetchSemanticsNodes().isNotEmpty())
        shot("medfind_qty_picker_360_bn")
        rule.onNodeWithTag("qty_unit_pcs").performClick()
        rule.onNodeWithTag("qty_input").performTextReplacement("15")
        rule.waitForIdle()
        assertEquals(15, sentQty)
        assertEquals("pcs", sentUnit)
        // ৯৯৯-এর বেশি নেয় না
        rule.onNodeWithTag("qty_input").performTextReplacement("0")
        rule.waitForIdle()
        assertEquals(15, sentQty)
    }

    @Test
    fun qtyPicker360En() {
        AppPrefs.lang.value = Lang.EN
        picker(napa)
        assertTrue(rule.onAllNodesWithText("How Much Do You Need").fetchSemanticsNodes().isNotEmpty())
        rule.onNodeWithTag("qty_unit_strip").assertTextContains("Strip")
        rule.onNodeWithTag("qty_unit_pcs").assertTextContains("Pieces")
        for (t in listOf("qty_plus", "qty_unit_pcs")) assertInside360(t)
        shot("medfind_qty_picker_360_en")
    }

    @Test
    fun qtyPickerSyrupBottle360Bn() {
        picker(syrup)
        rule.onAllNodesWithTag("qty_unit_pcs").assertCountEquals(0)
        rule.onNodeWithTag("qty_unit_bottle").assertTextContains("বোতল")
        assertEquals("bottle", sentUnit)
        shot("medfind_qty_picker_syrup_360_bn")
    }

    // ---------------- রোগী: আমার খোঁজ ----------------

    private fun myFind() = MyFind(
        "mf1", FindDrug(3, "Napa Extend", "Paracetamol 665 mg", "Tablet", "Beximco"), "ময়মনসিংহ সদর, ময়মনসিংহ", true, 8, 2,
        listOf(
            FindShop("ph1", "লাইফ কেয়ার ফার্মেসি", "01711000501", "হাসপাতাল রোড, চরপাড়া", true, true, 0.6, 12, "pcs", true),
            FindShop("ph2", "নিউ মডেল ড্রাগ হাউস অ্যান্ড মেডিসিন কর্নার", "01811000602", "গাঙ্গিনাপাড়, ছোটবাজার", true, false, 1.8, 4, "pcs", false),
        ),
        now - 25 * 60_000L, 10, "pcs",
    )

    private fun myFindCard() = show {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
            MyFindCard(myFind(), onCall = {}, onReport = {}, onClose = {})
        }
    }

    @Test
    fun myFindReplies360Bn() {
        myFindCard()
        rule.onNodeWithTag("find_need").assertTextContains("১০ পিস লাগবে")
        val replies = rule.onAllNodesWithTag("find_reply").fetchSemanticsNodes().map { it.config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString("") }
        assertEquals(listOf("আছে — ১২ পিস · পুরোটা আছে", "আছে — ৪ পিস (আপনার দরকার ১০)"), replies)
        assertTrue(rule.onAllNodes(hasText("নেই")).fetchSemanticsNodes().isEmpty())
        assertInside360("find_reply")
        shot("medfind_my_find_360_bn")
    }

    @Test
    fun myFindReplies360En() {
        AppPrefs.lang.value = Lang.EN
        myFindCard()
        rule.onNodeWithTag("find_need").assertTextContains("Needs 10 pcs")
        shot("medfind_my_find_360_en")
    }

    // ---------------- ফার্মেসি: কতটা আছে ----------------

    private val answers = ArrayList<Pair<String, Int?>>()

    private fun requests() = show {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                FindRequest("r1", napa, "চরপাড়া", 0.8, null, now - 10 * 60_000L, 3, "strip"),
                FindRequest("r2", FindDrug(4, "Sergel", "Esomeprazole 20 mg", "Capsule", "Healthcare"), "গাঙ্গিনাপাড়", 1.6, "yes", now - 40 * 60_000L, 14, "pcs", 10, "pcs"),
            ).forEach { r ->
                FindRequestCard(r, busy = false, enabled = true, onAnswer = { answers.add(r.id to it) }, onDismiss = { answers.add(r.id to null) })
            }
        }
    }

    @Test
    fun pharmacyAnswer360Bn() {
        requests()
        val needs = rule.onAllNodesWithTag("find_need").fetchSemanticsNodes().map { it.config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString("") }
        assertEquals(listOf("৩ পাতা লাগবে", "১৪ পিস লাগবে"), needs)
        rule.onNodeWithTag("find_have_unit").assertTextContains("পাতা")
        rule.onNodeWithTag("find_said").assertTextContains("আপনার উত্তর: আছে — ১০ পিস")
        // "নেই" বোতাম নেই; চুপচাপ "বাদ দিন"
        assertTrue(rule.onAllNodes(hasText("নেই")).fetchSemanticsNodes().isEmpty())
        rule.onNodeWithTag("find_dismiss").assertTextContains("বাদ দিন")
        for (t in listOf("find_have_qty", "find_have_send", "find_dismiss", "find_said")) assertInside360(t)
        shot("medfind_pharmacy_answer_360_bn")
        // ভুল সংখ্যা আটকায়, তারপর ৫ পাতা যায়
        rule.onNodeWithTag("find_have_qty").performTextReplacement("0")
        rule.onNodeWithTag("find_have_send").performClick()
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithText("১ থেকে ৯৯৯৯-এর মধ্যে একটা সংখ্যা লিখুন").fetchSemanticsNodes().isNotEmpty())
        assertTrue(answers.isEmpty())
        rule.onNodeWithTag("find_have_qty").performTextReplacement("৫")
        rule.onNodeWithTag("find_have_send").performClick()
        rule.waitForIdle()
        assertEquals(listOf("r1" to 5), answers)
    }

    @Test
    fun pharmacyDismissAndChange360Bn() {
        requests()
        rule.onNodeWithTag("find_dismiss").performClick()
        rule.waitForIdle()
        assertEquals(listOf<Pair<String, Int?>>("r1" to null), answers)
        // আগের উত্তর বদলানো: বদলান → আগের সংখ্যা ঘরে → নতুন সংখ্যা
        rule.onNodeWithText("বদলান").performClick()
        rule.waitForIdle()
        rule.onAllNodesWithTag("find_have_qty")[1].assertTextContains("10")
        rule.onAllNodesWithTag("find_have_qty")[1].performTextReplacement("1200")
        rule.onAllNodesWithTag("find_have_send")[1].performClick()
        rule.waitForIdle()
        assertEquals("r2" to 1200, answers.last())
    }

    @Test
    fun pharmacyAnswer360En() {
        AppPrefs.lang.value = Lang.EN
        requests()
        rule.onAllNodesWithTag("find_need")[0].assertTextContains("Needs 3 strips")
        rule.onNodeWithTag("find_have_unit").assertTextContains("strips")
        rule.onNodeWithTag("find_dismiss").assertTextContains("Dismiss")
        for (t in listOf("find_have_qty", "find_have_send", "find_dismiss")) assertInside360(t)
        shot("medfind_pharmacy_answer_360_en")
    }
}
