package com.bspc.blood

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.google.firebase.FirebaseApp
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * নতুন স্ক্রিনগুলো নমুনা তথ্য দিয়ে আঁকা হয় আর ছবি তোলা হয় (app/build/screens ফোল্ডারে png)।
 * ক্র্যাশ হলে টেস্ট ব্যর্থ হয়; ছবি দেখে চোখে যাচাই করা যায়।
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ScreensTest {
    @get:Rule
    val rule = createComposeRule()

    private val profile = Profile(name = "Rahim", phone = "01712345678", bloodGroup = "B+", lat = 24.75, lng = 90.4, placeName = "ময়মনসিংহ", locationSource = "district")
    private val nurseMe = CommunityMe(BadgeState("nurse", "approved"), isAdmin = false)

    private val chamber = Chamber(
        "dghs_5", "Mymensingh Medical College Hospital", "ময়মনসিংহ মেডিকেল কলেজ হাসপাতাল", "ময়মনসিংহ", null, null,
        "Room 204", "Closed on public holidays", "500 Tk", true, "01700000000", listOf(6, 0, 1, 2), "17:00", "21:00",
    )
    private val provider = Provider(
        "u1", "nurse", "Nasrin Akter", listOf("general_nursing", "home_care"), "BSc Nursing", "female", true, true,
        listOf(chamber), HomeVisitSettings(true, 5, emptyList(), listOf("injection", "saline", "dressing"), "Every day 8 AM – 10 PM", "300 Tk"),
        4.6, 12,
    )

    private fun visit(role: String, status: String, canRate: Boolean = false) = HomeVisit(
        "v$role$status", role, "broadcast", listOf("injection", "dressing"), 0L, "ময়মনসিংহ", null, "ময়মনসিংহ",
        "Patient is 70, bedridden", true, true, status, 0L, 0, canRate,
        if (status == "open" && role == "patient") "" else "Nasrin Akter", null,
        if (status == "accepted" || status == "on_the_way") "01712345678" else "",
        if (role == "nurse" && status == "open") "" else "House 12, Road 3, Ganginarpar",
        if (role == "nurse") 2.4 else null, null, null, 18, true,
    )

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        if (FirebaseApp.getApps(ctx).isEmpty()) runCatching { FirebaseApp.initializeApp(ctx) }
        Areas.ensure(ctx)
        AppPrefs.lang.value = Lang.EN
        AppPrefs.theme.value = ThemeMode.LIGHT
    }

    private fun show(content: @Composable () -> Unit) {
        rule.setContent { GetBloodTheme { Surface { content() } } }
        rule.waitForIdle()
    }

    private fun shot(name: String) {
        rule.waitForIdle()
        captureScreenRoboImage("build/screens/$name.png")
    }

    @Test
    fun visitCards() {
        show {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                VisitCard(visit("patient", "accepted"), null, {}, {}, {})
                VisitCard(visit("nurse", "open"), null, {}, {}, {})
                VisitCard(visit("patient", "done", canRate = true), null, {}, {}, {})
            }
        }
        shot("visit_cards_en")
    }

    @Test
    fun visitCardsBangla() {
        AppPrefs.lang.value = Lang.BN
        show {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                VisitCard(visit("nurse", "on_the_way"), null, {}, {}, {})
                VisitCard(visit("patient", "open"), null, {}, {}, {})
            }
        }
        shot("visit_cards_bn")
    }

    @Test
    fun providerCardAndDetail() {
        show { Column(Modifier.padding(16.dp)) { ProviderCard(provider, CareFilter()) {} } }
        shot("provider_card")
    }

    @Test
    fun providerDetailDialog() {
        show { ProviderDetailDialog(provider, profile, {}, {}) }
        shot("provider_detail")
    }

    @Test
    fun chamberEditor() {
        show { Column(Modifier.padding(16.dp)) { ChamberEditor(chamber, {}, {}, {}) } }
        shot("chamber_editor")
    }

    @Test
    fun chamberEditScreenNurse() {
        show { ChamberEditScreen("nurse", profile) {} }
        rule.waitUntil(5_000) { true }
        shot("chamber_edit_screen")
    }

    @Test
    fun homeNurseTab() {
        show { HomeNurseTab(profile, nurseMe) }
        shot("home_nurse_tab")
    }

    @Test
    fun homeVisitRequestDialog() {
        show { HomeVisitRequestDialog(profile, null, {}, {}) }
        rule.onNodeWithText("Injection").performClick()
        shot("home_visit_request")
    }

    @Test
    fun appointmentTab() {
        show { AppointmentTab(profile, nurseMe, {}, {}) }
        shot("appointment_tab")
    }

    @Test
    fun addHospitalDialog() {
        show { AddHospitalDialog("Popular Diagnostic", "ময়মনসিংহ", null, {}, {}, {}) }
        shot("add_hospital")
    }

    @Test
    fun bisectAreaChipsInDialog() {
        show {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = {},
                confirmButton = {},
                text = { Column(Modifier.verticalScroll(rememberScrollState())) { AreaChips("ময়মনসিংহ", null, null, { _, _, _ -> }, allowAllDistricts = false) } },
            )
        }
        shot("bisect_areachips_dialog")
    }

    @Test
    fun bisectTypeChipsInDialog() {
        show {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = {},
                confirmButton = {},
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Hospitals.TYPES.chunked(2).forEach { row ->
                            androidx.compose.foundation.layout.Row {
                                row.forEach { t -> androidx.compose.material3.FilterChip(selected = false, onClick = {}, label = { androidx.compose.material3.Text(Hospitals.typeLabel(t), maxLines = 1) }) }
                            }
                        }
                    }
                },
            )
        }
        shot("bisect_typechips_dialog")
    }

    @Test
    fun bisectAddHospitalNoIdle() {
        rule.mainClock.autoAdvance = false
        rule.setContent { GetBloodTheme { Surface { AddHospitalDialog("Popular Diagnostic", "ময়মনসিংহ", null, {}, {}, {}) } } }
        rule.mainClock.advanceTimeBy(3000)
        captureScreenRoboImage("build/screens/bisect_addhospital_frozen.png")
    }

    @Test
    fun hospitalPicker() {
        show { HospitalPickerDialog("ময়মনসিংহ", null, {}, {}) }
        shot("hospital_picker")
    }

    @Test
    fun upazilaPickerWithThanas() {
        show {
            SearchPickDialog(
                title = "Upazila",
                items = Areas.upazilasOf("ঢাকা"),
                label = { it.label },
                onPick = {},
                onDismiss = {},
            )
        }
        shot("dhaka_upazilas")
    }

    @Test
    fun adminHospitalRequests() {
        val h = Hospital("r1", "Popular Diagnostic Centre", "পপুলার ডায়াগনস্টিক", "diagnostic", "ময়মনসিংহ", null, null, "Charpara", "")
        show {
            Column(Modifier.padding(16.dp)) {
                AdminHospitalRequests(listOf(HospitalRequestItem(h, 3, listOf(h.copy(id = "dghs_1", nameEn = "Popular Diagnostic Center Ltd")))), null) { _, _, _ -> }
            }
        }
        shot("admin_hospitals")
    }

    @Test
    fun phoneVerifyDialog() {
        show { PhoneVerifyDialog({}, {}) }
        shot("phone_verify")
    }
}
