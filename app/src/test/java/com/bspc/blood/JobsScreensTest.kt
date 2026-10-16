package com.bspc.blood

// FEATURE:jobs — পুরো ফাইলটা চাকরি ফিচারের

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** চাকরি আর সিভির পাতা নমুনা তথ্যে আঁকা (app/build/screens) */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class JobsScreensTest {
    @get:Rule
    val rule = createComposeRule()

    private val job = Job(
        "j1", "সেলস এক্সিকিউটিভ", "রহমান ট্রেডার্স", true, "sales", "fulltime", "ময়মনসিংহ", null,
        15000, 22000, 2, 1, "hsc", listOf("বিক্রয়", "এমএস এক্সেল"), System.currentTimeMillis() + 10 * DAY_MS, 0, 3, "open", myStatus = "shortlisted",
    )
    private val cv = Cv(
        uid = "u1", name = "Karim Uddin", district = "ময়মনসিংহ", headline = "হিসাবরক্ষক, ৩ বছরের অভিজ্ঞতা",
        about = "হিসাব আর অফিসের কাজে দক্ষ।",
        experience = listOf(CvExp("হিসাবরক্ষক", "আল-আমিন স্টোর", "2021-03", "", true, "দৈনিক হিসাব, স্টক আর বিল")),
        education = listOf(CvEdu("বিকম", "আনন্দ মোহন কলেজ", 2020, "দ্বিতীয় শ্রেণি")),
        skills = listOf("এমএস এক্সেল", "Tally"), languages = listOf("বাংলা", "English"),
        openToWork = true, wantCategories = listOf("accounts", "office"), complete = true, phone = "01712345678",
    )

    @Before
    fun setUp() {
        Areas.ensure(ApplicationProvider.getApplicationContext())
        AppPrefs.lang.value = Lang.BN
    }

    private fun show(content: @Composable () -> Unit) {
        rule.setContent { GetBloodTheme { Surface { content() } } }
        rule.waitForIdle()
    }

    @Test
    fun jobsAndCvBangla() {
        show {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                JobCard(job, onSave = {}) {}
                ApplicationStatusTag("interview")
                ApplicationNextStep("interview", AppExtras("", Interview(System.currentTimeMillis() + 2 * DAY_MS, "inperson", "রহমান ট্রেডার্স, গাঙ্গিনারপাড়", "", "pending", ""), null, "", ""))
                ApplicationNextStep("offered", AppExtras("", null, Offer("সেলস এক্সিকিউটিভ", 18000, Rules.todayUtcMidnight() + 7 * DAY_MS, Rules.todayUtcMidnight() + 2 * DAY_MS, "pending"), "", ""))
                ApplicationNextStep("rejected", AppExtras("filled", null, null, "", ""))
                Text(JobText.salaryOf(job.copy(salaryNegotiable = true, salaryMin = 0, salaryMax = 0)))
                Text(JobText.salaryOf(job.copy(type = "internship", paid = false, durationMonths = 3)))
                CvHeader(cv)
                CvBody(cv)
                Text(JobText.salary(15000, 22000))
            }
        }
        captureScreenRoboImage("build/screens/jobs_cv_bn.png")
        assertEquals("৳১৫,০০০ – ২২,০০০/মাস", JobText.salary(15000, 22000))
        assertEquals("মার্চ ২০২১", CvApi.ym("2021-03"))
        assertEquals("2021-03", CvApi.ymCode(2021, 3))
    }

    @Test
    fun cvPdf() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val f = java.io.File(ctx.cacheDir, "cv.pdf")
        CvPdf.write(ctx, android.net.Uri.fromFile(f), cv.copy(about = "লম্বা লেখা ".repeat(400)))
        assertTrue(f.length() > 500)
    }
}
