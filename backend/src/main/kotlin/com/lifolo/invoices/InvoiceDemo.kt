package com.lifolo.invoices

import com.lifolo.admin.kernel.DemoSeedContext
import com.lifolo.admin.kernel.DemoSeedPart
import com.lifolo.auth.AuthUser
import org.springframework.stereotype.Component
import java.math.BigDecimal

/**
 * Demo receipts (docs/INVOICES.md) — a few of each kind, most for the demo patient (my_invoices / my_expenses), one
 * with money due and one void. Runs in the last demo step, after the shop, lab, doctor and ambulance exist; the demo
 * refresh builds them again every new Dhaka day with the rest of the demo world.
 */
@Component
class InvoiceDemo(private val invoices: InvoiceService) : DemoSeedPart {
    override val step = 5
    override val label = "স্বাস্থ্য ও কেয়ারগিভার"
    override val order = 100

    private fun bd(v: Int) = BigDecimal(v)

    private fun line(name: String, qty: Int, price: Int, disc: Int = 0) = LineIn(name, bd(qty), bd(price), bd(disc))

    override fun seed(ctx: DemoSeedContext) {
        val patient = ctx.user("patient")
        val pName = Customer(patient.name, "01700000101")

        fun issue(kind: String, bizId: String, ownerUid: String, cmd: (String) -> IssueCmd): Invoice? {
            val b = invoices.biz(kind, bizId) ?: return null
            if (!invoices.canIssue(b) || b.ownerUid != ownerUid) return null
            return invoices.issue("bn", AuthUser(ownerUid), cmd(bizId))
        }

        // pharmacy: a prescription sale for the patient, a counter sale with money due, one voided by mistake
        val shop = ctx.user("pharmacy").uid
        issue("pharmacy", shop, shop) {
            IssueCmd(
                "pharmacy", it, listOf(line("নাপা ৫০০ মি.গ্রা.", 20, 2), line("সেকলো ২০ মি.গ্রা.", 14, 7, 5)), // bn-ok
                customer = pName, patientUid = patient.uid,
            )
        }
        issue("pharmacy", shop, shop) {
            IssueCmd(
                "pharmacy", it, listOf(line("ইনসুলিন পেন", 1, 950), line("সিরিঞ্জ", 10, 8)), bd(18), // bn-ok
                payments = listOf(PaymentIn(bd(500), "cash")), customer = Customer("করিম সাহেব"), // bn-ok
            )
        }
        issue("pharmacy", shop, shop) { IssueCmd("pharmacy", it, listOf(line("ওআরএস", 5, 6)), customer = Customer("ভুল বিক্রি")) } // bn-ok
            ?.let { invoices.void("bn", AuthUser(shop), it.id, "wrong_item", "ভুল করে লেখা হয়েছিল") } // bn-ok

        // lab: the patient's tests with the home-collection fee, paid by bKash
        val lab = ctx.user("lab").uid
        issue("lab", lab, lab) {
            IssueCmd(
                "lab", it, listOf(LineIn("সিবিসি", BigDecimal.ONE, bd(400), code = "cbc"), LineIn("আরবিএস", BigDecimal.ONE, bd(150), code = "rbs")), // bn-ok
                bd(55), listOf(FeeIn("হোম কালেকশন", bd(200))), listOf(PaymentIn(bd(695), "bkash", "8N7A6D5C4B")), pName, patient.uid, // bn-ok
            )
        }

        // doctor: a chamber fee for the patient and a walk-in follow-up
        val doctor = ctx.user("doctor").uid
        issue("chamber", doctor, doctor) {
            IssueCmd(
                "chamber", it, listOf(line("ভিজিট ফি — নতুন রোগী", 1, 800)), customer = pName, patientUid = patient.uid, // bn-ok
                bizAddress = "শহীদ তাজউদ্দীন আহমদ মেডিকেল কলেজ হাসপাতাল", // bn-ok
            )
        }
        issue("chamber", doctor, doctor) {
            IssueCmd("chamber", it, listOf(line("ভিজিট ফি — ফলো-আপ", 1, 400)), customer = Customer("হাসিনা বেগম")) // bn-ok
        }

        // ambulance: the patient's trip to the hospital (fare within the chart)
        val amb = ctx.user("ambulance").uid
        issue("ambulance", "demo-svc-amb-ac", amb) {
            IssueCmd(
                "ambulance", it, listOf(line("ভাড়া — ঢাকা", 1, 2500)), fees = listOf(FeeIn("অক্সিজেন", bd(500))), // bn-ok
                payments = listOf(PaymentIn(bd(3000), "cash")), customer = pName, patientUid = patient.uid, overFare = false,
            )
        }
    }
}
