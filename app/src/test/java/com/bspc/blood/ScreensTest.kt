package com.bspc.blood

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onAllNodesWithText
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

    private val profile = Profile(name = "Rahim", phone = "01712345678", bloodGroup = "B+", lat = 24.75, lng = 90.4, placeName = "ময়মনসিংহ", locationSource = "district")
    private val nurseMe = CommunityMe(BadgeState("nurse", "approved"), isAdmin = false)

    private val chamber = Chamber(
        "dghs_5", "Mymensingh Medical College Hospital", "ময়মনসিংহ মেডিকেল কলেজ হাসপাতাল", "ময়মনসিংহ", null, null,
        "Room 204", "Closed on public holidays", "500 Tk", true, "01700000000", listOf(6, 0, 1, 2), "17:00", "21:00",
    )
    private val provider = Provider(
        "u1", "nurse", "Nasrin Akter", listOf("general_nursing", "home_care"), "BSc Nursing", "female", true, true,
        listOf(chamber), HomeVisitSettings(true, 5, emptyList(), listOf("injection", "saline", "dressing"), "Every day 8 AM – 10 PM", "300 Tk", "01712345678"),
        4.6, 12,
    )

    private fun homePro(type: String, name: String, services: List<String>, km: Double?, called: Boolean) = HomePro(
        "h$type", type, name, if (type == "doctor") listOf("medicine") else listOf("general_nursing"),
        if (type == "doctor") "MBBS, FCPS (Medicine)" else "BSc Nursing", "female", services, 5, listOf(1),
        "Every day 8 AM – 10 PM", "500 Tk + transport", "01712345678", km, 4.6, 12, called, 0,
    )

    private val request = BloodRequest(
        id = "r1", patientGroup = "B+", bags = 3, hospital = "Mymensingh Medical College Hospital", placeName = "ময়মনসিংহ",
        neededByMillis = System.currentTimeMillis() + 86_400_000L, urgent = true, notified = true, notifiedCount = 23,
    )
    private val donors = listOf(
        DonorResponse("d1", "Karim Uddin", "01811111111", "B+", "accepted", confirmed = true, distanceKm = 3),
        DonorResponse("d2", "Sumon Mia", "01922222222", "O-", "accepted", distanceKm = 12, fareAfterKm = 5),
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
    fun homeProCards() {
        show {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                HomeProCard(homePro("doctor", "Dr. Farhana Islam", listOf("checkup", "elderly_care", "bedridden"), 2.4, true), {}, {}, {})
                HomeProCard(homePro("nurse", "Nasrin Akter", listOf("injection", "saline", "dressing"), null, false), {}, null, {})
            }
        }
        shot("home_pro_cards_en")
    }

    @Test
    fun homeProCardsBangla() {
        AppPrefs.lang.value = Lang.BN
        show {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                HomeProCard(homePro("doctor", "ডা. ফারহানা ইসলাম", listOf("checkup", "follow_up", "child"), 3.0, true), {}, {}, {})
                HomeProCard(homePro("nurse", "নাসরিন আক্তার", listOf("injection", "bp_sugar", "newborn_care"), null, false), {}, null, {})
            }
        }
        shot("home_pro_cards_bn")
    }

    @Test
    fun myRequestCardBangla() {
        AppPrefs.lang.value = Lang.BN
        show {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                MyRequestCard(request, donors, null, {}, {}, {}, {}, {})
                MyRequestCard(request.copy(id = "r2", urgent = false, notified = false), emptyList(), null, {}, {}, {}, {}, {})
            }
        }
        shot("my_request_card_bn")
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
    fun closeRequestDialogBangla() {
        AppPrefs.lang.value = Lang.BN
        show { CloseRequestDialog("r1", 3, donors, {}, { _, _ -> }) }
        rule.onNodeWithText("২").performClick()
        shot("close_request_dialog_bn")
    }

    @Test
    fun bloodBankCardsBangla() {
        AppPrefs.lang.value = Lang.BN
        show {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                BloodBankCard(
                    BloodBank("b1", "ময়মনসিংহ মেডিকেল কলেজ হাসপাতাল ব্লাড ব্যাংক", "govt", "01712345678", "ময়মনসিংহ", null, "চরপাড়া", true, "", "", true, listOf("whole_blood", "components", "screening", "donate_here")),
                    {}, {},
                )
                BloodBankCard(
                    BloodBank("b2", "সন্ধানী, ময়মনসিংহ", "org", "01812345678", "ময়মনসিংহ", null, "মেডিকেল কলেজ ক্যাম্পাস", false, "09:00", "17:00", false, listOf("whole_blood", "donate_here")),
                    {}, {},
                )
            }
        }
        shot("blood_bank_cards_bn")
    }

    @Test
    fun servicesBloodBankBangla() {
        AppPrefs.lang.value = Lang.BN
        show { ServicesTab(profile) }
        rule.onNodeWithText("ব্লাড ব্যাংক").performClick()
        shot("services_blood_bank_bn")
    }

    @Test
    fun requestCardsBangla() {
        AppPrefs.lang.value = Lang.BN
        show {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                RequestCard(request, 3.4) {}
                RequestCard(request.copy(id = "r3", bags = 1, urgent = false, patientGroup = "O-"), 8.0) {}
            }
        }
        shot("request_cards_bn")
    }

    @Test
    fun emergencyCardsBangla() {
        AppPrefs.lang.value = Lang.BN
        val groups = listOf(EmergencyGroup("B+", 6, 6, true), EmergencyGroup("O+", 4, 1, false), EmergencyGroup("A+", 3, 0, false))
        val base = EmergencyAppeal(
            "e1", "ময়মনসিংহ মেডিকেল কলেজ হাসপাতাল", "ময়মনসিংহ", "01711000000", "বাস দুর্ঘটনা — জরুরি বিভাগের ব্লাড ব্যাংকে আসুন",
            groups, System.currentTimeMillis() + 6 * 3_600_000L, "O+", "notified", canCome = true, eligible = true,
        )
        show {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                EmergencyCard(base) {}
                EmergencyCard(base.copy(id = "e2", myStatus = "coming", note = "")) {}
                EmergencyCard(base.copy(id = "e3", myGroup = "B+", canCome = false, note = "")) {}
            }
        }
        shot("emergency_cards_bn")
    }

    @Test
    fun doctorListFilterBarBangla() {
        AppPrefs.lang.value = Lang.BN
        show { AppointmentTab(profile, nurseMe, {}, {}) }
        shot("doctor_filter_bar_bn")
        rule.onNodeWithText("ফিল্টার").performClick()
        rule.waitForIdle()
        shot("doctor_filter_sheet_bn")
    }

    @Test
    fun ambulanceFilterBarBangla() {
        AppPrefs.lang.value = Lang.BN
        show { ServicesTab(profile) }
        shot("ambulance_filter_bar_bn")
    }

    @Test
    fun navBarAndAreaPickerBangla() {
        AppPrefs.lang.value = Lang.BN
        val items = listOf(
            NavItem("হোম", R.drawable.ic_nav_home_fill, R.drawable.ic_nav_home),
            NavItem("অ্যাপয়েন্টমেন্ট", R.drawable.ic_nav_event_fill, R.drawable.ic_nav_event),
            NavItem("স্বাস্থ্য", R.drawable.ic_nav_health_fill, R.drawable.ic_nav_health),
            NavItem("সেবা", R.drawable.ic_nav_services_fill, R.drawable.ic_nav_services),
        )
        show {
            Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    FilterBar(areaText("ময়মনসিংহ", 1), {}, filterCount = 2, onFilterClick = {}, search = "", onSearch = {})
                }
                ModernNavBar(items, 1) {}
            }
        }
        shot("nav_bar_filter_bar_bn")
    }

    @Test
    fun areaPickerBangla() {
        AppPrefs.lang.value = Lang.BN
        show { AreaSheet(Districts.ALL.first { it.en == "Mymensingh" }.bn, null, null, { _, _, _ -> }, {}, nearMe = false, onNearMe = {}) }
        shot("area_picker_bn")
        runCatching { rule.onAllNodesWithText("জেলা বা উপজেলা লিখুন")[0].performTextInput("ত্রিশ") }
        rule.waitForIdle()
        shot("area_picker_search_bn")
    }

    @Test
    fun donationDateDialogBangla() {
        AppPrefs.lang.value = Lang.BN
        show { DonationDateDialog(profile.copy(lastDonationMillis = Rules.todayUtcMidnight() - 40 * DAY_MS), {}, {}) }
        shot("donation_date_dialog_bn")
    }

    @Test
    fun titleCaseRules() {
        org.junit.Assert.assertEquals("Change Last Donation Date", TitleCase.ui("Change last donation date"))
        org.junit.Assert.assertEquals("Find a Medicine", TitleCase.ui("Find a medicine"))
        org.junit.Assert.assertEquals("Within 5 km", TitleCase.ui("Within 5 km"))
        org.junit.Assert.assertEquals("ICU / Critical Care", TitleCase.ui("ICU / Critical care"))
        org.junit.Assert.assertEquals("Fee: ", TitleCase.ui("Fee: "))
        // বাক্য বদলায় না
        org.junit.Assert.assertEquals("Call and agree on the time and fee.", TitleCase.ui("Call and agree on the time and fee."))
        org.junit.Assert.assertEquals("Emergency? 999 is free, 24 hours", TitleCase.ui("Emergency? 999 is free, 24 hours"))
        // নাম
        org.junit.Assert.assertEquals("Mymensingh Medical College Hospital", TitleCase.name("mymensingh  medical college hospital"))
        org.junit.Assert.assertEquals("Bank of Asia", TitleCase.name("bank of asia"))
        org.junit.Assert.assertEquals("McDonald", TitleCase.name("McDonald"))
        org.junit.Assert.assertEquals("রহিম উদ্দিন", TitleCase.name("রহিম উদ্দিন"))
        // হাইফেন/ঊর্ধ্বকমার পরের অংশ
        org.junit.Assert.assertEquals("Al-Amin", TitleCase.name("al-amin"))
        org.junit.Assert.assertEquals("O'Brien", TitleCase.name("o'brien"))
        org.junit.Assert.assertEquals("Mother's Care Hospital", TitleCase.name("mother's care hospital"))
        // বার্তা Title Case ছাড়া
        org.junit.Assert.assertEquals("Enter a valid email", TitleCase.plain(TitleCase.ui("Enter a valid email")))
    }

    @Test
    fun logo() {
        show { Column(Modifier.padding(16.dp)) { Logo(iconSize = 30.dp) } }
        shot("logo")
    }

    /** সরু পর্দায় শেষের ট্যাব চাপলে সারি নিজে সরে ট্যাবটা পুরো দেখায় */
    @Test
    fun pillTabsAutoScroll() {
        AppPrefs.lang.value = Lang.BN
        show {
            var sel by remember { mutableIntStateOf(0) }
            Column(Modifier.width(260.dp)) {
                PillTabs(sel, listOf(
                    PillTab("অ্যাম্বুলেন্স", R.drawable.ic_ambulance), PillTab("ফার্মেসি", R.drawable.ic_medication), PillTab("ব্লাড ব্যাংক", R.drawable.ic_bloodtype),
                ), { sel = it })
            }
        }
        rule.onNodeWithText("ব্লাড ব্যাংক").performClick()
        rule.mainClock.advanceTimeBy(1000)
        shot("pill_tabs_autoscroll_bn")
    }

    /** ২.৯: আবার জানান, শেষ হওয়া রিকোয়েস্টে রক্ত দিয়েছি/দিইনি, "আর লাগবে না" */
    @Test
    fun requestGapFixesBangla() {
        AppPrefs.lang.value = Lang.BN
        val now = System.currentTimeMillis()
        show {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                MyRequestCard(
                    request.copy(confirmedCount = 1, notifyRounds = 1, lastNotifiedMillis = now - 7 * 3_600_000L),
                    donors, null, {}, {}, {}, {}, {},
                )
                ProcessingCard(
                    request.copy(id = "p1", status = "closed", closedAtMillis = now - 3_600_000L),
                    onClick = {}, onDonated = {}, onNotDonated = {},
                )
                RequestCard(request.copy(id = "f1", bags = 3, confirmedCount = 1), 2.5) {}
            }
        }
        shot("request_gap_fixes_bn")
    }

    @Test
    fun closeDialogNotNeededBangla() {
        AppPrefs.lang.value = Lang.BN
        show { CloseRequestDialog("r1", 3, donors, {}, { _, _ -> }) }
        rule.onNodeWithText("আর লাগবে না").performClick()
        shot("close_dialog_not_needed_bn")
    }

    /** ২.১২: জরুরি আহ্বান শেষে "রক্ত দিয়েছেন?", মালিকের কার্ডে বাতিলের কারণ, সাপ্তাহিক বন্ধ */
    @Test
    fun accountGapFixesBangla() {
        AppPrefs.lang.value = Lang.BN
        show {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                EmergencyAskCard(EmergencyAsk("e1", "ময়মনসিংহ মেডিকেল কলেজ হাসপাতাল", 0L)) {}
                StatusReasonLine("rejected", "লাইসেন্স যাচাই করা যায়নি")
                Text(closedDaysText(listOf(5)))
                ErrorText(tr("Enter a valid email", "সঠিক ইমেইল দিন"))
            }
        }
        shot("account_gaps_bn")
    }

    /** ২.৮: "জানি না" রক্তের গ্রুপ, বাদ দেওয়া ডোনার ফিরিয়ে আনা */
    @Test
    fun gapFixesBangla() {
        AppPrefs.lang.value = Lang.BN
        show {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                BloodGroupChips(selected = Rules.UNKNOWN_GROUP, onSelect = {}, allowUnknown = true)
                RemovedNotice("Karim Uddin", busy = false, onUndo = {}, onDismiss = {})
                BloodGroupBadge(Rules.UNKNOWN_GROUP, 44.dp)
            }
        }
        shot("gap_fixes_bn")
    }

    /** উপরের বার: লোগো, ঘণ্টা, ছবি+☰ মেনু (ছবি ছাড়া ও ছবিসহ) */
    @Test
    fun topBarMenuAvatar() {
        AppPrefs.lang.value = Lang.BN
        val pic = android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888)
            .apply { eraseColor(0xFFB07A5A.toInt()) }
            .asImageBitmap()
        show {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                listOf(null, pic).forEach { a ->
                    androidx.compose.foundation.layout.Row(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Logo(iconSize = 30.dp)
                        androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                        BellButton(2) {}
                        androidx.compose.material3.IconButton(onClick = {}) { MenuAvatar(a) }
                    }
                }
            }
        }
        shot("top_bar_menu_avatar")
    }

    @Test
    fun englishFontServices() {
        show { ServicesTab(profile) }
        shot("services_en_font")
    }

    @Test
    fun englishTitleCaseScreens() {
        show { AppointmentTab(profile, nurseMe, {}, {}) }
        shot("appointment_tab_en_titlecase")
    }

    @Test
    fun homeCareTab() {
        AppPrefs.lang.value = Lang.BN
        show { HomeCareTab(profile, nurseMe) {} }
        shot("home_care_tab_bn")
    }

    @Test
    fun chamberEditScreenDoctorHome() {
        AppPrefs.lang.value = Lang.BN
        show { ChamberEditScreen("doctor", profile) {} }
        // লোড শেষে সেটিং খোলা (নেটওয়ার্ক নেই, তাই না এলে শুধু ছবি)
        runCatching {
            rule.waitUntil(5_000) { rule.onAllNodesWithText("আমি রোগীর বাসায় গিয়ে সেবা দিই").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("আমি রোগীর বাসায় গিয়ে সেবা দিই").performClick()
        }
        shot("chamber_edit_doctor_home_bn")
    }

    @Test
    fun appointmentTab() {
        show { AppointmentTab(profile, nurseMe, {}, {}) }
        shot("appointment_tab")
    }

    @Test
    fun appointmentTabBangla() {
        AppPrefs.lang.value = Lang.BN
        show { AppointmentTab(profile, nurseMe, {}, {}) }
        rule.onNodeWithText("বাসায় সেবা").performClick()
        shot("appointment_tab_home_bn")
    }

    @Test
    fun addHospitalDialog() {
        show { AddHospitalDialog("Popular Diagnostic", "ময়মনসিংহ", null, {}, {}, {}) }
        shot("add_hospital")
    }

    @Composable
    private fun FilledField() = androidx.compose.material3.OutlinedTextField(value = "Popular", onValueChange = {}, label = { androidx.compose.material3.Text("Name in English") })

    @Composable
    private fun TypeChips() = Hospitals.TYPES.chunked(2).forEach { row ->
        androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            row.forEach { t -> androidx.compose.material3.FilterChip(selected = false, onClick = {}, label = { androidx.compose.material3.Text(Hospitals.typeLabel(t), maxLines = 1) }) }
        }
    }

    private fun alert(body: @Composable () -> Unit) = show {
        androidx.compose.material3.AlertDialog(onDismissRequest = {}, confirmButton = {}, text = { Column(Modifier.verticalScroll(rememberScrollState())) { body() } })
    }

    @Test
    fun formDialogWithFieldAndChips() {
        show { FormDialog("Form", {}, content = { Column { FilledField(); TypeChips() } }, confirmButton = {}, dismissButton = {}) }
        shot("form_dialog")
    }

    @Test
    fun hospitalPicker() {
        show { HospitalPickerDialog("ময়মনসিংহ", null, {}, {}) }
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
        val h = Hospital("r1", "Popular Diagnostic Centre", "পপুলার ডায়াগনস্টিক", "diagnostic", "ময়মনসিংহ", null, null, "Charpara", "")
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

    @Test
    fun servicesTab() {
        show { ServicesTab(profile) }
        shot("services_tab")
    }

    @Test
    fun medicineFinder() {
        show { MedFindPanel(profile) }
        shot("med_find")
    }

    @Test
    fun pharmacyDirectory() {
        show { PharmacyContent(profile) }
        shot("pharmacy_content")
    }

    @Test
    fun ambulanceMine() {
        show { AmbulanceContent(profile) }
        shot("ambulance_content")
    }

    @Test
    fun ambulanceScreen() {
        show { AmbulanceScreen(profile) {} }
        shot("ambulance_screen")
    }

    @Test
    fun helpScreen() {
        show { HelpScreen("pharmacy") {} }
        shot("help_appeal")
    }

    @Test
    fun healthTab() {
        show { HealthTab(0, nurseMe, {}, {}, {}, {}) }
        shot("health_tab")
    }
}
