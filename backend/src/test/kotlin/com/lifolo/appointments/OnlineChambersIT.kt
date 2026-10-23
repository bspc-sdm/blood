package com.lifolo.appointments

import com.fasterxml.jackson.databind.JsonNode
import com.lifolo.contracts.HospitalDirectory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * Online (video) chambers + the bKash fee flow (V620, docs/CHAMBERS-ONLINE.md): provider_save/provider_me/providers
 * (mode, bkashNumber hidden in the public list, note), the online filter, book without a hospital, my_appointments and
 * rx_patients fields, appt_pay_claim / appt_pay_confirm / appt_pay_reject (who may, states, TrxID, pushes), no fee,
 * deactivated accounts (423), demo ↔ real.
 */
class OnlineChambersIT : P2TestBase() {
    @Autowired lateinit var hospitals: HospitalDirectory

    private val day = 24 * 3600 * 1000L
    private val doc = "olDoc"
    private val asst = "olAsst"
    private val pat = "olPat"
    private val cg = "olCg"
    private val x = "olStranger"

    private fun inPerson(hospitalId: String) =
        mapOf("hospitalId" to hospitalId, "days" to listOf(0, 1, 2, 3, 4, 5, 6), "start" to "09:00", "end" to "13:00", "appointments" to true, "fee" to "৫০০ টাকা")

    private fun online(extra: Map<String, Any?> = emptyMap()) = mapOf(
        "mode" to "online", "days" to listOf(0, 1, 2, 3, 4, 5, 6), "start" to "18:00", "end" to "21:00", "appointments" to true,
        "fee" to "৪০০ টাকা", "bkashNumber" to "+880 1712-345678", "note" to " ভিডিও কলের আগে রিপোর্টের ছবি তৈরি রাখুন ", "patientMin" to 10,
    ) + extra

    private fun save(uid: String, chambers: List<Any?>) = call(uid, "provider_save", mapOf("chambers" to chambers, "specialties" to listOf("medicine"), "appointments" to true))

    /** the doctor (one chamber at a hospital + the online one), an assistant, a patient with a caregiver */
    private fun seed(fixedClock: Boolean = true): String {
        // rx_patients reads the real Dhaka day, so that test runs on the real clock (online chamber 18–21, booked ahead)
        clock.fixedMs = if (fixedClock) dhaka("2026-10-19 10:00") else null
        val h = hospitals.approvedIn(district("Gazipur")).first()
        person(doc, "ডা. অনলাইন")
        badge(doc, "doctor", "ডা. অনলাইন")
        person(asst, "সহকারী", "asst@online.test")
        person(pat, "রোগী রহিম")
        person(cg, "কেয়ারগিভার")
        person(x, "অন্য কেউ")
        jdbc.update("INSERT INTO care_links (patient_uid, caretaker_uid, status) VALUES (?, ?, 'active')", pat, cg)
        val r = save(doc, listOf(inPerson(h.id), online()))
        assertEquals(200, r.status, r.toString())
        jdbc.update("INSERT INTO provider_assistants (provider_uid, email, position) VALUES (?, 'asst@online.test', 0)", doc)
        return h.id
    }

    private fun book(uid: String = pat, date: Long = today() + day, extra: Map<String, Any?> = emptyMap()): String =
        ok(uid, "book", mapOf("providerUid" to doc, "date" to date, "chamberIndex" to 1, "note" to "জ্বর") + extra)["id"].textValue()

    private fun appt(id: String) = row("SELECT * FROM appointments WHERE id = ?", id)

    private fun myItem(uid: String, id: String, extra: Map<String, Any?> = emptyMap()): JsonNode =
        ok(uid, "my_appointments", extra)["items"].first { it["id"].textValue() == id }

    /** the phone pushes to [uid] with this title */
    private fun pushed(uid: String, title: String) = push.messages.filter { it.uid == uid && it.data["title"] == title }

    @Test
    fun `provider_save - online chamber shape, validation, the public list hides the bKash number`() {
        val hid = seed()
        val me = ok(doc, "provider_me")["provider"]["chambers"]
        assertEquals(listOf("in_person", "online"), me.map { it["mode"].textValue() })
        val oc = me[1]
        assertTrue(oc["hospitalId"].isNull)
        assertEquals("Online video consultation", oc["hospitalName"].textValue())
        assertEquals("অনলাইন ভিডিও পরামর্শ", oc["hospitalNameBn"].textValue())
        assertTrue(oc["district"].isNull)
        assertFalse(oc.has("address"))
        assertEquals("01712345678", oc["bkashNumber"].textValue(), "normalized")
        assertEquals("ভিডিও কলের আগে রিপোর্টের ছবি তৈরি রাখুন", oc["note"].textValue())
        assertEquals(listOf("৪০০ টাকা", "18:00", "21:00", "10"), listOf(oc["fee"].textValue(), oc["start"].textValue(), oc["end"].textValue(), oc["patientMin"].asText()))
        assertFalse(me[0].has("bkashNumber"))
        val stored = row("SELECT hospital_id, district, address, mode, bkash_number FROM provider_chambers WHERE provider_uid = ? AND idx = 1", doc)
        assertEquals(listOf(null, null, null, "online", "01712345678"), listOf(stored["hospital_id"], stored["district"], stored["address"], stored["mode"], stored["bkash_number"]))

        // the public list: mode and note, never the bKash number
        val pub = ok(x, "providers", mapOf("type" to "doctor", "homeVisit" to false))["items"].first { it["uid"].textValue() == doc }
        assertEquals("online", pub["chambers"][1]["mode"].textValue())
        assertTrue(pub["chambers"].none { it.has("bkashNumber") })
        val guest = ok(null, "providers", mapOf("type" to "doctor", "homeVisit" to false))["items"].first { it["uid"].textValue() == doc }
        assertTrue(guest["chambers"].none { it.has("bkashNumber") })

        // validation
        fun err(chambers: List<Any?>, uid: String = doc): String? = save(uid, chambers).also { assertEquals(400, it.status, it.toString()) }.error
        assertEquals("আপনার বিকাশ নম্বর দিন (01XXXXXXXXX)", err(listOf(online(mapOf("bkashNumber" to "")))))
        assertEquals("আপনার বিকাশ নম্বর দিন (01XXXXXXXXX)", err(listOf(online(mapOf("bkashNumber" to "12345")))))
        assertEquals("আপনার বিকাশ নম্বর দিন (01XXXXXXXXX)", err(listOf(online(mapOf("noFee" to true, "bkashNumber" to "0171")))), "a wrong number even without a fee")
        assertEquals("অনলাইন চেম্বারের ফি লিখুন (টাকা)", err(listOf(online(mapOf("fee" to "আলোচনা সাপেক্ষে")))))
        assertEquals("অনলাইন চেম্বার একটাই রাখা যায়", err(listOf(online(), online(mapOf("start" to "06:00", "end" to "07:00")))))
        assertEquals("অনলাইন চেম্বারে কোন কোন বার বসেন বেছে নিন", err(listOf(online(mapOf("days" to emptyList<Int>())))))
        assertEquals(400, save(doc, listOf(inPerson(hid), online(mapOf("start" to "10:00", "end" to "12:00")))).status, "cannot sit online and at the hospital at once")
        // up to 3 chambers at a place + 1 online
        val hs = hospitals.approvedIn(district("Gazipur")).take(3)
        val three = hs.mapIndexed { i, h -> inPerson(h.id) + mapOf("start" to "0${i + 1}:00", "end" to "0${i + 1}:30") }
        ok(doc, "provider_save", mapOf("chambers" to three + online(), "specialties" to listOf("medicine"), "appointments" to true))
        assertEquals(hs.size + 1, ok(doc, "provider_me")["provider"]["chambers"].size())
        // no fee: no bKash number needed; fee 0 = no fee
        ok(doc, "provider_save", mapOf("chambers" to listOf(online(mapOf("noFee" to true, "bkashNumber" to ""))), "specialties" to listOf("medicine"), "appointments" to true))
        val free = ok(doc, "provider_me")["provider"]["chambers"][0]
        assertEquals(listOf(true, "", ""), listOf(free["noFee"].booleanValue(), free["fee"].textValue(), free["bkashNumber"].textValue()))
        ok(doc, "provider_save", mapOf("chambers" to listOf(online(mapOf("fee" to "০ টাকা"))), "specialties" to listOf("medicine"), "appointments" to true))
        assertTrue(ok(doc, "provider_me")["provider"]["chambers"][0]["noFee"].booleanValue())

        // doctors only (a nutritionist's chamber is always at a place)
        person("olNutri")
        badge("olNutri", "nutritionist")
        val nutri = call("olNutri", "provider_save", mapOf("chambers" to listOf(online()), "specialties" to listOf("clinical_nutrition"), "appointments" to true))
        assertEquals("অনলাইন চেম্বার শুধু ডাক্তারদের জন্য", nutri.error)

        // old data and old apps: no mode = at a place
        ok(doc, "provider_save", mapOf("chambers" to listOf(inPerson(hid)), "specialties" to listOf("medicine"), "appointments" to true))
        assertEquals("in_person", ok(doc, "provider_me")["provider"]["chambers"][0]["mode"].textValue())
        assertEquals("in_person", row("SELECT mode FROM provider_chambers WHERE provider_uid = ?", doc)["mode"])
    }

    @Test
    fun `providers - online filter ignores the area, area filters skip online-only doctors`() {
        seed()
        val gzp = district("Gazipur")
        // a doctor with only an online chamber
        person("olOnly", "ডা. শুধু অনলাইন")
        badge("olOnly", "doctor", "ডা. শুধু অনলাইন")
        ok("olOnly", "provider_save", mapOf("chambers" to listOf(online()), "specialties" to listOf("pediatrics"), "appointments" to true))
        fun ids(extra: Map<String, Any?>): List<String> =
            ok(x, "providers", mapOf("type" to "doctor", "homeVisit" to false) + extra)["items"].map { it["uid"].textValue() }.sorted()
        assertEquals(listOf(doc, "olOnly").sorted(), ids(emptyMap()))
        assertEquals(listOf(doc), ids(mapOf("district" to gzp)), "no district for an online chamber")
        assertEquals(listOf(doc, "olOnly").sorted(), ids(mapOf("online" to true, "district" to district("Dhaka"))), "online: wherever they are")
        assertEquals(listOf("olOnly"), ids(mapOf("online" to true, "specialty" to "pediatrics")))
        assertEquals(listOf(doc), ids(mapOf("online" to false)))
        val r = ok(x, "providers", mapOf("type" to "doctor", "homeVisit" to false, "online" to true))
        assertEquals(listOf(1), r["items"].first { it["uid"].textValue() == doc }["matched"].map { it.intValue() })
        assertEquals(listOf(doc, "olOnly").sorted(), ids(mapOf("online" to true, "day" to "today", "maxFee" to 500)))
        assertEquals(emptyList<String>(), ids(mapOf("online" to true, "maxFee" to 300)))
        assertEquals(listOf(doc, "olOnly").sorted(), ids(mapOf("hospitalQ" to "অনলাইন")), "the Bangla label finds them")
    }

    @Test
    fun `book online - no hospital, pay fields, my_appointments both roles, rx_patients, bKash number follows edits`() {
        val hid = seed(fixedClock = false)
        val today = today()
        val id = book()
        val a = appt(id)
        assertEquals(listOf(true, "unpaid", "online", "01712345678", "৪০০ টাকা", false, "Online video consultation", "অনলাইন ভিডিও পরামর্শ", "18:00"),
            listOf(a["online"], a["pay_state"], a["chamber_key"], a["bkash_number"], a["fee"], a["no_fee"], a["chamber"], a["chamber_bn"], a["start_time"]))
        assertEquals(1, pushesTo(doc, "নতুন অনলাইন অ্যাপয়েন্টমেন্টের অনুরোধ").size)
        // a chamberKey sent: "online" is the online chamber's key
        val id2 = book(date = today + 2 * day, extra = mapOf("chamberKey" to "online"))
        assertEquals(409, call(pat, "book", mapOf("providerUid" to doc, "date" to today + 3 * day, "chamberIndex" to 1, "note" to "x", "chamberKey" to "dghs_x")).status)
        // in person: online false, nothing to pay
        val inp = ok(pat, "book", mapOf("providerUid" to doc, "date" to today + 4 * day, "chamberIndex" to 0, "note" to "কাশি"))["id"].textValue()
        assertEquals(listOf(false, null), appt(inp).let { listOf(it["online"], it["pay_state"]) })

        ok(doc, "appointment_update", mapOf("id" to id, "op" to "confirm"))
        assertEquals(1, pushesTo(pat, "অনলাইন অ্যাপয়েন্টমেন্ট নিশ্চিত হয়েছে").size)
        val p = myItem(pat, id)
        assertEquals(listOf("true", "unpaid", "null", "৪০০ টাকা", "false", "ভিডিও কলের আগে রিপোর্টের ছবি তৈরি রাখুন", "01712345678", "18:00"),
            listOf(p["online"].asText(), p["payState"].asText(), p["payRef"].asText(), p["fee"].asText(), p["noFee"].asText(), p["onlineNote"].asText(), p["bkashNumber"].asText(), p["start"].asText()))
        val d = myItem(doc, id)
        assertEquals(listOf("provider", "true", "unpaid"), listOf(d["role"].asText(), d["online"].asText(), d["payState"].asText()))
        assertFalse(d.has("bkashNumber"), "the doctor side has no bKash field")
        val pi = myItem(pat, inp)
        assertEquals(listOf(false, true, true), listOf(pi["online"].booleanValue(), pi["payState"].isNull, pi["bkashNumber"].isNull))
        // the caregiver sees the patient side
        assertEquals("01712345678", myItem(cg, id, mapOf("asUid" to pat))["bkashNumber"].textValue())

        // rx_patients (doctor and assistant)
        val rp = ok(asst, "rx_patients", mapOf("doctorUid" to doc))["appointments"].first { it["id"].textValue() == id }
        assertEquals(listOf("true", "unpaid", "null", "18:00"), listOf(rp["online"].asText(), rp["payState"].asText(), rp["payRef"].asText(), rp["start"].asText()))

        // the doctor changes the bKash number: open online appointments show the new one
        ok(doc, "provider_save", mapOf("chambers" to listOf(inPerson(hid), online(mapOf("bkashNumber" to "01812345678", "fee" to "৬০০ টাকা"))), "specialties" to listOf("medicine"), "appointments" to true))
        assertEquals(listOf("01812345678", "৪০০ টাকা"), appt(id2).let { listOf(it["bkash_number"], it["fee"]) }, "the fee stays as booked")
        // the online chamber removed: its appointments are cancelled
        val r = ok(doc, "provider_save", mapOf("chambers" to listOf(inPerson(hid)), "specialties" to listOf("medicine"), "appointments" to true))
        assertEquals(2, r["cancelled"].intValue())
        assertEquals("cancelled", appt(id)["status"])
        assertEquals("pending", appt(inp)["status"])

        // no fee: nothing to pay
        ok(doc, "provider_save", mapOf("chambers" to listOf(inPerson(hid), online(mapOf("noFee" to true))), "specialties" to listOf("medicine"), "appointments" to true))
        val freeId = book(date = today + 5 * day)
        assertEquals(listOf(true, null, true, ""), appt(freeId).let { listOf(it["online"], it["pay_state"], it["no_fee"], it["fee"]) })
        val fi = myItem(pat, freeId)
        assertEquals(listOf(true, true, true), listOf(fi["payState"].isNull, fi["noFee"].booleanValue(), fi["bkashNumber"].isNull))
        val nf = call(pat, "appt_pay_claim", mapOf("appointmentId" to freeId))
        assertEquals(409, nf.status)
        assertEquals("no_fee", nf.code)
        assertEquals("no_fee", call(doc, "appt_pay_confirm", mapOf("appointmentId" to freeId)).code)
    }

    @Test
    fun `pay flow - claim, confirm, reject, who may, states, pushes`() {
        seed()
        val id = book()
        val inp = ok(pat, "book", mapOf("providerUid" to doc, "date" to today() + 2 * day, "chamberIndex" to 0, "note" to "কাশি"))["id"].textValue()
        fun claim(uid: String, body: Map<String, Any?> = emptyMap(), appt: String = id) = call(uid, "appt_pay_claim", mapOf("appointmentId" to appt) + body)

        // who may claim: the patient (or the caregiver), nobody else
        assertEquals(404, claim(x).status)
        assertEquals(404, claim(doc).status)
        assertEquals(404, claim(pat, appt = "nosuchappt1").status)
        assertEquals(404, call(pat, "appt_pay_claim").status)
        val notOnline = claim(pat, appt = inp)
        assertEquals(409, notOnline.status)
        assertEquals("not_online", notOnline.code)
        // TrxID: letters and digits, 6–30
        assertEquals("বিকাশ মেসেজের TrxID লিখুন (ইংরেজি অক্ষর ও সংখ্যা)", claim(pat, mapOf("payRef" to "টাকা দিয়েছি")).error)
        assertEquals(400, claim(pat, mapOf("payRef" to "AB1")).status)
        assertEquals("unpaid", appt(id)["pay_state"])

        // pending is enough for a claim
        val c = ok(pat, "appt_pay_claim", mapOf("appointmentId" to id, "payRef" to " 9fk2 m7q4 ab "))
        assertEquals(listOf("true", "claimed", "9FK2M7Q4AB"), listOf(c["ok"].asText(), c["payState"].asText(), c["payRef"].asText()))
        assertEquals(listOf("claimed", "9FK2M7Q4AB"), appt(id).let { listOf(it["pay_state"], it["pay_ref"]) })
        val title = "রোগী ফি পাঠিয়েছেন — যাচাই করুন"
        assertEquals(1, pushesTo(doc, title).size)
        assertEquals(1, pushesTo(asst, title).size, "the assistant too")
        assertTrue(pushesTo(doc, title).last().second.contains("TrxID 9FK2M7Q4AB"))
        val msg = pushed(doc, title).single()
        assertEquals(listOf("appointments", "appointment", "provider", id), listOf(msg.data["cat"], msg.data["type"], msg.data["role"], msg.data["id"]))
        assertTrue(pushesTo(x, title).isEmpty())
        // again without a TrxID: the earlier one stays, no second push
        assertEquals("9FK2M7Q4AB", ok(pat, "appt_pay_claim", mapOf("appointmentId" to id))["payRef"].textValue())
        assertEquals(1, pushesTo(doc, title).size)
        // the caregiver may say it for the patient (a new TrxID → told again)
        assertEquals("CG12345678", ok(cg, "appt_pay_claim", mapOf("asUid" to pat, "appointmentId" to id, "payRef" to "cg12345678"))["payRef"].textValue())
        assertEquals(2, pushesTo(doc, title).size)
        val myP = myItem(pat, id)
        assertEquals(listOf("claimed", "CG12345678"), listOf(myP["payState"].textValue(), myP["payRef"].textValue()))
        assertEquals("claimed", myItem(doc, id)["payState"].textValue())

        // who may confirm/reject: the doctor or the assistant; not the patient, a caregiver or a stranger
        assertEquals(404, call(pat, "appt_pay_confirm", mapOf("appointmentId" to id)).status)
        assertEquals(404, call(x, "appt_pay_confirm", mapOf("appointmentId" to id)).status)
        assertEquals(403, call(cg, "appt_pay_confirm", mapOf("asUid" to pat, "appointmentId" to id)).status, "not a care action")
        assertEquals(404, call(x, "appt_pay_reject", mapOf("appointmentId" to id)).status)
        assertEquals("not_online", call(doc, "appt_pay_confirm", mapOf("appointmentId" to inp)).code)

        // "পাইনি": back to unpaid with the reason; the patient is told
        val rj = ok(doc, "appt_pay_reject", mapOf("appointmentId" to id, "reason" to " এই TrxID-তে টাকা আসেনি "))
        assertEquals(listOf("unpaid", "এই TrxID-তে টাকা আসেনি"), listOf(rj["payState"].textValue(), rj["payNote"].textValue()))
        assertEquals(listOf("unpaid", "এই TrxID-তে টাকা আসেনি"), appt(id).let { listOf(it["pay_state"], it["pay_note"]) })
        assertTrue(pushesTo(pat, "ডাক্তার এখনো ফি পাননি").last().second.endsWith("এই TrxID-তে টাকা আসেনি"))
        assertEquals("এই TrxID-তে টাকা আসেনি", myItem(pat, id)["payNote"].textValue())
        val again = call(doc, "appt_pay_reject", mapOf("appointmentId" to id))
        assertEquals(409, again.status)
        assertEquals("state", again.code)

        // claimed again (the note goes), then the assistant confirms; the patient (and the caregiver) are told
        ok(pat, "appt_pay_claim", mapOf("appointmentId" to id, "payRef" to "AB12CD34EF"))
        assertNull(appt(id)["pay_note"])
        val pc = ok(asst, "appt_pay_confirm", mapOf("appointmentId" to id))
        assertEquals(listOf("true", "paid"), listOf(pc["ok"].asText(), pc["payState"].asText()))
        assertEquals(listOf("paid", asst), appt(id).let { listOf(it["pay_state"], it["pay_paid_by"]) })
        assertEquals(1, pushesTo(pat, "ডাক্তার ফি পেয়েছেন").size)
        assertEquals("appointments", pushed(pat, "ডাক্তার ফি পেয়েছেন").single().data["cat"])
        assertEquals(1, pushesTo(cg, "ডাক্তার ফি পেয়েছেন").size, "the caregiver's copy")
        // paid: confirming again changes nothing (no second push); no claim or reject any more
        assertEquals("paid", ok(doc, "appt_pay_confirm", mapOf("appointmentId" to id))["payState"].textValue())
        assertEquals(1, pushesTo(pat, "ডাক্তার ফি পেয়েছেন").size)
        assertEquals(409, claim(pat).status)
        assertEquals(409, call(doc, "appt_pay_reject", mapOf("appointmentId" to id)).status)
        assertEquals("paid", myItem(pat, id)["payState"].textValue())

        // the doctor may mark an unpaid one paid directly (money came without a claim)
        val id2 = book(date = today() + 3 * day)
        ok(doc, "appointment_update", mapOf("id" to id2, "op" to "confirm"))
        assertEquals("paid", ok(doc, "appt_pay_confirm", mapOf("appointmentId" to id2))["payState"].textValue())
        // a cancelled appointment: no claim
        val id3 = book(date = today() + 4 * day)
        ok(pat, "appointment_update", mapOf("id" to id3, "op" to "cancel"))
        assertEquals("state", claim(pat, appt = id3).code)
        assertEquals("state", call(doc, "appt_pay_confirm", mapOf("appointmentId" to id3)).code)

        // an assistant of a doctor whose badge is gone
        val id4 = book(date = today() + 5 * day)
        badge(doc, "doctor", "ডা. অনলাইন", status = "rejected")
        assertEquals(403, call(asst, "appt_pay_confirm", mapOf("appointmentId" to id4)).status)
    }

    @Test
    fun `deactivated accounts get 423, demo and real never mix`() {
        seed()
        val id = book()
        val id2 = book(date = today() + 2 * day)
        // the patient deactivated: 423 for the claim
        assertEquals(200, call(pat, "account_deactivate").status)
        val r = call(pat, "appt_pay_claim", mapOf("appointmentId" to id))
        assertEquals(423, r.status)
        assertEquals("account_deactivated", r.code)
        assertEquals("unpaid", appt(id)["pay_state"])
        // the doctor deactivated: 423 for confirm / reject
        assertEquals(200, call(doc, "account_deactivate").status)
        assertEquals(423, call(doc, "appt_pay_confirm", mapOf("appointmentId" to id2)).status)
        assertEquals(423, call(doc, "appt_pay_reject", mapOf("appointmentId" to id2)).status)
        assertEquals(423, call(doc, "provider_save", mapOf("chambers" to listOf(online()))).status)

        // demo ↔ real: a demo patient cannot book the real online chamber, nor touch a real appointment
        person("demo-olpat", "ডেমো রোগী")
        assertEquals(200, call(doc, "account_reactivate").status)
        assertEquals(404, call("demo-olpat", "book", mapOf("providerUid" to doc, "date" to today() + 3 * day, "chamberIndex" to 1, "note" to "x")).status)
        assertEquals(404, call("demo-olpat", "appt_pay_claim", mapOf("appointmentId" to id)).status)
        assertEquals(404, call("demo-olpat", "appt_pay_confirm", mapOf("appointmentId" to id)).status)
        // a demo doctor with an online chamber: demo patients book it (pay flow works inside the demo world)
        person("demo-oldoc", "ডা. ডেমো অনলাইন")
        badge("demo-oldoc", "doctor", "ডা. ডেমো অনলাইন")
        ok("demo-oldoc", "provider_save", mapOf("chambers" to listOf(online()), "specialties" to listOf("medicine"), "appointments" to true))
        val did = ok("demo-olpat", "book", mapOf("providerUid" to "demo-oldoc", "date" to today() + day, "chamberIndex" to 0, "note" to "x"))["id"].textValue()
        assertEquals(404, call(x, "appt_pay_confirm", mapOf("appointmentId" to did)).status)
        assertEquals("claimed", ok("demo-olpat", "appt_pay_claim", mapOf("appointmentId" to did))["payState"].textValue())
        assertEquals("paid", ok("demo-oldoc", "appt_pay_confirm", mapOf("appointmentId" to did))["payState"].textValue())
    }
}
