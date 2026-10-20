package com.lifolo.demo

import com.fasterxml.jackson.databind.ObjectMapper
import com.lifolo.admin.kernel.DemoSeedContext
import com.lifolo.admin.kernel.DemoSeedPart
import com.lifolo.common.DhakaTime
import com.lifolo.demo.DemoSql.Companion.DAY
import com.lifolo.demo.DemoSql.Companion.HOUR
import org.springframework.stereotype.Component

/**
 * demo/health.js — runs last: health Q&A, tips, professional badges (an expert, a nutritionist waiting with a
 * certificate photo, a rejected trainer who appealed), moderation reports, caregivers and the patient's family, the
 * bell list, help & appeals tickets, and an old "last donation date correction" request. Profiles come from the blood
 * part (only merged here). Medicine alarms live only on the phone, so there is nothing to seed for them.
 */
@Component
class DemoHealth(private val sql: DemoSql, private val mapper: ObjectMapper) : DemoSeedPart {
    override val step = 5
    override val label = "স্বাস্থ্য ও কেয়ারগিভার"

    private val jdbc get() = sql.jdbc

    /** a verification sample photo (JPEG, "DEMO CERTIFICATE — NOT REAL") */
    private val certJpeg: String by lazy { DemoSql.resource("/com/lifolo/demo/demo-cert.b64") }

    private class Who(val uid: String, val name: String, val badge: String = "")

    private class Ans(val id: String, val who: Who, val text: String, val at: Long)

    override fun seed(ctx: DemoSeedContext) {
        val now = ctx.nowMs
        val day = DAY
        val hr = HOUR
        val today = DhakaTime.todayUtcMidnight(now)
        val dist = DemoSql.GAZIPUR
        fun nameOf(key: String) = ctx.user(key).name

        // ---------- background people (no login) ----------
        fun bg(key: String, name: String, phone: String, extra: Map<String, Any?> = emptyMap()): String =
            ctx.extraUser(key, linkedMapOf<String, Any?>("name" to name, "phone" to phone, "place_name" to dist, "lang" to "bn").apply { putAll(extra) })
        val nutri = bg("h-nutri", "ডেমো পুষ্টিবিদ ফারহানা", "01200000401")
        val trainer = bg("h-trainer", "ডেমো ফিটনেস ট্রেইনার মিঠু", "01200000402")
        val expert = bg("h-expert", "ডেমো স্বাস্থ্য বিশেষজ্ঞ জামাল", "01200000403")
        val mother = bg("h-mother", "ডেমো রোগী আমেনা খাতুন", "01200000404")
        val asker = bg("h-asker", "ডেমো প্রশ্নকারী সাকিব", "01200000405")
        val spam = bg("h-spam", "ডেমো হারবাল সেন্টার", "01200000406")
        val oldDonor = bg("h-olddonor", "ডেমো ডোনার মাসুদ", "01200000407", mapOf("last_donation_ms" to today - 40 * day))

        // ---------- professional badges ----------
        fun approved(type: String, checks: List<String>, at: Long) = linkedMapOf<String, Any?>(
            "type" to type, "status" to "approved", "reviewed_at" to sql.ts(at), "reviewed_by" to "demo",
            "verified_by" to "demo", "verified_by_email" to "", "verified_at" to sql.ts(at), "verification_checks" to sql.texts(checks),
        )
        fun hasBadge(uid: String) = jdbc.queryForList("SELECT 1 FROM badges WHERE uid = ?", uid).isNotEmpty()
        val doctor = ctx.user("doctor")
        val homecare = ctx.user("homecare")
        // the doctor's and nurse's badges when the other parts did not write them (else no answers/tips)
        if (!hasBadge(doctor.uid)) {
            sql.insert(
                "badges",
                linkedMapOf<String, Any?>(
                    "uid" to doctor.uid, "reg_no" to "ডেমো-A-12345", "workplace" to "ডেমো জয়দেবপুর জেনারেল হাসপাতাল, গাজীপুর", "name" to nameOf("doctor"), "email" to doctor.email,
                    "created_at" to sql.ts(now - 40 * day),
                ).apply { putAll(approved("doctor", listOf("bmdc_found", "bmdc_name", "bmdc_active", "workplace"), now - 39 * day)) },
            )
        }
        if (!hasBadge(homecare.uid)) {
            sql.insert(
                "badges",
                linkedMapOf<String, Any?>(
                    "uid" to homecare.uid, "reg_no" to "ডেমো-BNMC-4521", "workplace" to "ডেমো ছায়াবীথি নার্সিং হোম, গাজীপুর", "name" to nameOf("homecare"), "email" to homecare.email,
                    "created_at" to sql.ts(now - 35 * day), "docs_have" to sql.texts(listOf("cert")),
                ).apply { putAll(approved("nurse", listOf("cert_no", "cert_name", "cert_valid", "workplace"), now - 34 * day)) },
            )
        }
        // a health expert (approved) — answers and tips; one of his answers was reported
        sql.insert(
            "badges",
            linkedMapOf<String, Any?>(
                "uid" to expert, "reg_no" to "ডেমো-EXP-0007", "workplace" to "ডেমো জনস্বাস্থ্য প্রকল্প, জয়দেবপুর", "name" to "ডেমো স্বাস্থ্য বিশেষজ্ঞ জামাল", "email" to "",
                "created_at" to sql.ts(now - 30 * day), "docs_have" to sql.texts(listOf("cert")),
            ).apply { putAll(approved("expert", listOf("cert_name", "cert_relevant", "workplace"), now - 29 * day)) },
        )
        // a nutritionist (waiting, with a certificate photo) — the owner looks at it in the web panel, ticks and approves
        sql.insert(
            "badges",
            linkedMapOf<String, Any?>(
                "uid" to nutri, "type" to "nutritionist", "reg_no" to "ডেমো-NUT-0042", "workplace" to "ডেমো পুষ্টি পরামর্শ কেন্দ্র, জয়দেবপুর, গাজীপুর", "name" to "ডেমো পুষ্টিবিদ ফারহানা", "email" to "",
                "status" to "pending", "created_at" to sql.ts(now - 20 * hr), "docs_have" to sql.texts(listOf("cert")),
            ),
        )
        sql.docs("badge", nutri, nutri, listOf("cert"), now - 20 * hr, certJpeg)
        // a fitness trainer (rejected, with a reason) — he appealed (ticket below)
        sql.insert(
            "badges",
            linkedMapOf<String, Any?>(
                "uid" to trainer, "type" to "trainer", "reg_no" to "ডেমো-FIT-0310", "workplace" to "ডেমো ফিটনেস ক্লাব, শিববাড়ি", "name" to "ডেমো ফিটনেস ট্রেইনার মিঠু", "email" to "",
                "status" to "rejected", "status_reason" to "সনদের ছবি অস্পষ্ট, নাম পড়া যায় না", "created_at" to sql.ts(now - 6 * day), "reviewed_at" to sql.ts(now - 5 * day),
                "reviewed_by" to "demo", "docs_have" to sql.texts(listOf("cert")),
            ),
        )
        sql.docs("badge", trainer, trainer, listOf("cert"), now - 6 * day, certJpeg)

        // ---------- health discussion: questions and answers ----------
        fun q(id: String, by: Who, text: String, at: Long, anonymous: Boolean = false, answers: List<Ans> = emptyList()) {
            val last = answers.maxOfOrNull { it.at }
            sql.insert(
                "questions",
                linkedMapOf(
                    "id" to id, "uid" to by.uid, "author_name" to by.name, "anonymous" to anonymous, "text" to text, "created_at" to sql.ts(at),
                    "answer_count" to answers.size, "last_answer_at" to sql.ts(last),
                ),
            )
            for (a in answers) {
                sql.insert(
                    "answers",
                    linkedMapOf(
                        "id" to a.id, "question_id" to id, "uid" to a.who.uid, "author_name" to a.who.name, "badge" to a.who.badge,
                        "text" to a.text, "created_at" to sql.ts(a.at),
                    ),
                )
            }
        }
        val p = Who(ctx.user("patient").uid, nameOf("patient"))
        val doc = Who(doctor.uid, nameOf("doctor"), "doctor")
        val exp = Who(expert, "ডেমো স্বাস্থ্য বিশেষজ্ঞ জামাল", "expert")

        q(
            "demo-q-diabetes", p, "আমার টাইপ-২ ডায়াবেটিস আছে, সকালে-রাতে মেটফরমিন খাই। সামনে রোজা — রোজা রাখা কি নিরাপদ? সেহরি আর ইফতারে ওষুধ কীভাবে খাব?", now - 8 * day,
            answers = listOf(
                Ans("demo-a-diab-doc", doc, "রক্তে শর্করা নিয়ন্ত্রণে থাকলে বেশিরভাগ টাইপ-২ রোগী রোজা রাখতে পারেন। মেটফরমিন সাধারণত ইফতার আর সেহরিতে ভাগ করে খাওয়া হয়, তবে ডোজ ঠিক করবেন আপনার ডাক্তার। দিনের বেলা শর্করা ৩.৯-এর নিচে (৭০) বা ১৬.৭-এর ওপরে (৩০০) গেলে সাথে সাথে রোজা ভাঙবেন। রোজার আগে একবার চেম্বারে দেখিয়ে নিন।", now - 7 * day),
                Ans("demo-a-diab-exp", exp, "ইফতারে একসাথে বেশি মিষ্টি আর ভাজাপোড়া না খেয়ে খেজুর, পানি, ডাল-সবজি দিয়ে শুরু করুন। সেহরি একদম শেষ সময়ে খান, আর ইফতার থেকে সেহরি পর্যন্ত অন্তত ৮ গ্লাস পানি।", now - 6 * day),
            ),
        )
        q("demo-q-sleep", p, "কয়েক সপ্তাহ ধরে রাতে ঘুম আসে না, সকালে মাথা ভার থাকে। দোকান থেকে ঘুমের ওষুধ কিনে খাওয়া কি ঠিক হবে?", now - 36 * hr, anonymous = true, answers = listOf(Ans("demo-a-sleep-doc", doc, "ডাক্তারের পরামর্শ ছাড়া ঘুমের ওষুধ খাবেন না — অভ্যাস হয়ে যেতে পারে। রাতে মোবাইল কম দেখুন, প্রতিদিন একই সময়ে ঘুমাতে যান, বিকেলের পর চা-কফি বাদ দিন। দুই সপ্তাহে উন্নতি না হলে চেম্বারে আসুন।", now - 3 * hr)))
        q(
            "demo-q-fever", Who(asker, "ডেমো প্রশ্নকারী সাকিব"), "আমার ৪ বছরের বাচ্চার ৩ দিন ধরে জ্বর ১০২°, কাশিও আছে। প্যারাসিটামল দিচ্ছি। কখন হাসপাতালে নেওয়া দরকার?", now - 2 * day,
            answers = listOf(
                Ans("demo-a-fever-doc", doc, "শ্বাসকষ্ট, খিঁচুনি, খেতে না পারা, প্রস্রাব কমে যাওয়া বা জ্বর ৩ দিনের বেশি থাকলে আজই শিশু বিশেষজ্ঞ দেখান। ডেঙ্গুর মৌসুম — রক্ত পরীক্ষা (CBC, NS1) করিয়ে নিন। প্যারাসিটামল ওজন অনুযায়ী ৬ ঘণ্টা পরপর দিন; আইবুপ্রোফেন বা অ্যাসপিরিন দেবেন না।", now - 30 * hr),
                // wrong advice — reported (below); the owner removes or keeps it
                Ans("demo-a-fever-exp", exp, "জ্বর ৩ দিন থাকলে দোকান থেকে একটা অ্যান্টিবায়োটিক কিনে খাওয়ালেই সেরে যাবে, ডাক্তার দেখানোর দরকার নেই।", now - 20 * hr),
            ),
        )
        q("demo-q-donate", Who(ctx.user("donor").uid, nameOf("donor")), "রক্ত দেওয়ার পর দুই দিন একটু মাথা ঘোরে। এটা কি স্বাভাবিক? আবার কবে রক্ত দিতে পারব?", now - 5 * day, answers = listOf(Ans("demo-a-donate-doc", doc, "রক্তদানের পর ১–২ দিন হালকা মাথা ঘোরা স্বাভাবিক। প্রচুর পানি খান, ডিম, কলিজা, কচু শাক, ডাল খান। ১২০ দিন (৪ মাস) পরপর রক্ত দিতে পারবেন। মাথা ঘোরা বেশি দিন থাকলে হিমোগ্লোবিন পরীক্ষা করান।", now - 4 * day)))
        // no answer — the tester answers as the doctor/nurse
        q("demo-q-knee", Who(mother, "ডেমো রোগী আমেনা খাতুন"), "বয়স ৬২, দুই হাঁটুতে ব্যথা, সিঁড়ি দিয়ে উঠতে কষ্ট হয়। বাসায় কোন ব্যায়াম করা যায়? কোন খাবার এড়িয়ে চলব?", now - 26 * hr)
        q("demo-q-bedsore", Who(ctx.user("caregiver").uid, nameOf("caregiver")), "আমার শাশুড়ি প্রায় সবসময় শুয়ে থাকেন। পিঠে লাল দাগ হচ্ছে — বেড সোর যেন না হয় সেজন্য কী করব?", now - 6 * hr)
        // an advertisement — two people reported it
        q("demo-q-ad", Who(spam, "ডেমো হারবাল সেন্টার"), "গ্যারান্টি সহকারে ৭ দিনে ডায়াবেটিস চিরতরে ভালো করুন! ইনসুলিন লাগবে না। আমাদের হারবাল ওষুধ নিতে এখনই ফোন করুন ০১২০০০০০৪০৬ — ডেমো হারবাল সেন্টার", now - 10 * hr) // bn-ok (demo number moved to the 012 range nobody is issued)

        // ---------- health tips ----------
        fun tip(id: String, who: Who, title: String, body: String, at: Long) {
            sql.insert(
                "tips",
                linkedMapOf("id" to id, "uid" to who.uid, "author_name" to who.name, "badge" to who.badge, "title" to title, "body" to body, "created_at" to sql.ts(at)),
            )
        }
        val nurse = Who(homecare.uid, nameOf("homecare"), "nurse")
        tip("demo-tip-dengue", doc, "ডেঙ্গু জ্বরে যা করবেন, যা করবেন না", "জ্বর হলে প্রথম দিনেই NS1 পরীক্ষা করান। প্রচুর পানি, ডাবের পানি, স্যালাইন খান। জ্বরে শুধু প্যারাসিটামল — ব্যথানাশক (আইবুপ্রোফেন, ডাইক্লোফেনাক) বা অ্যাসপিরিন খাবেন না। পেটে ব্যথা, বমি, দাঁতের মাড়ি বা নাক দিয়ে রক্ত পড়লে দেরি না করে হাসপাতালে যান। বাসার আশেপাশে জমে থাকা পানি ফেলে দিন।", now - 2 * day)
        tip("demo-tip-bp", doc, "উচ্চ রক্তচাপ নিয়ন্ত্রণে ৫টি সহজ অভ্যাস", "১) তরকারিতে লবণ কমান, পাতে আলাদা লবণ নয়। ২) প্রতিদিন ৩০ মিনিট হাঁটুন। ৩) ধূমপান ও জর্দা বাদ দিন। ৪) ওষুধ নিজে নিজে বন্ধ করবেন না। ৫) মাসে অন্তত একবার রক্তচাপ মাপুন — শিববাড়ি বা জয়দেবপুর বাজারের যেকোনো ফার্মেসিতে মাপা যায়।", now - 6 * day)
        tip("demo-tip-winter", doc, "শীতে শিশুর ঠান্ডা-কাশি: কখন ডাক্তার দেখাবেন", "নাক বন্ধে নরমাল স্যালাইন ড্রপ দিন, গরম পানির ভাপ নিন। বুকের দুধ ও তরল খাবার চালু রাখুন। শ্বাস দ্রুত হলে, বুক দেবে গেলে, ঠোঁট নীল হলে বা খেতে না পারলে সাথে সাথে ডাক্তার দেখান। কাশির সিরাপ নিজে কিনে দেবেন না।", now - 11 * day)
        tip("demo-tip-insulin", nurse, "ইনসুলিন ঠিকভাবে রাখার নিয়ম", "না খোলা ইনসুলিন ফ্রিজের নিচের তাকে (২–৮°C) রাখুন, ফ্রিজারে নয়। খোলা কলম/ভায়াল ঘরের তাপমাত্রায় ২৮ দিন পর্যন্ত চলে — রোদ আর চুলার কাছ থেকে দূরে রাখুন। প্রতিবার সুই বদলান আর ইনজেকশনের জায়গা ঘুরিয়ে দিন।", now - 4 * day)
        tip("demo-tip-donor-food", exp, "রক্তদানের আগে ও পরে কী খাবেন", "রক্তদানের আগের রাতে ভালো ঘুমান, খালি পেটে রক্ত দেবেন না। আগে-পরে প্রচুর পানি খান। আয়রনের জন্য কলিজা, ডিম, ডাল, কচু শাক, পালং শাক খান; সাথে লেবু বা আমলকী খেলে আয়রন ভালো শোষণ হয়। রক্ত দেওয়ার দিন ভারী কাজ ও রোদে বেশি ঘোরাঘুরি এড়িয়ে চলুন।", now - 8 * day)

        // ---------- moderation reports ----------
        fun report(path: String, kind: String, reporter: String, snapshot: String, at: Long) {
            sql.insert(
                "mod_reports",
                linkedMapOf(
                    "id" to "${path.replace('/', '_')}__$reporter", "kind" to kind, "target_path" to path, "reporter_uid" to reporter, "reason" to "",
                    "snapshot" to snapshot, "status" to "open", "created_at" to sql.ts(at),
                ),
            )
        }
        val adText = "গ্যারান্টি সহকারে ৭ দিনে ডায়াবেটিস চিরতরে ভালো করুন! ইনসুলিন লাগবে না। আমাদের হারবাল ওষুধ নিতে এখনই ফোন করুন ০১২০০০০০৪০৬ — ডেমো হারবাল সেন্টার" // bn-ok (demo number moved to the 012 range nobody is issued)
        report("questions/demo-q-ad", "question", ctx.user("patient").uid, adText, now - 8 * hr)
        report("questions/demo-q-ad", "question", ctx.user("donor").uid, adText, now - 7 * hr)
        report("questions/demo-q-fever/answers/demo-a-fever-exp", "answer", ctx.user("patient").uid, "জ্বর ৩ দিন থাকলে দোকান থেকে একটা অ্যান্টিবায়োটিক কিনে খাওয়ালেই সেরে যাবে, ডাক্তার দেখানোর দরকার নেই।", now - 15 * hr)

        // ---------- caregivers and family ----------
        val pu = ctx.user("patient").uid
        val cgUser = ctx.user("caregiver")
        // patient Rahim → caregiver Ruma (active): she opens "as him" for prescriptions and serials
        sql.insert(
            "care_links",
            linkedMapOf(
                "patient_uid" to pu, "caretaker_uid" to cgUser.uid, "patient_name" to nameOf("patient"), "caretaker_name" to nameOf("caregiver"),
                "caretaker_email" to cgUser.email, "status" to "active", "created_at" to sql.ts(now - 6 * day), "accepted_at" to sql.ts(now - 6 * day + 2 * hr),
            ),
        )
        // Amena Khatun asked Ruma to be her caregiver (waiting) — Ruma presses "agree" / "no"
        sql.insert(
            "care_links",
            linkedMapOf(
                "patient_uid" to mother, "caretaker_uid" to cgUser.uid, "patient_name" to "ডেমো রোগী আমেনা খাতুন", "caretaker_name" to nameOf("caregiver"),
                "caretaker_email" to cgUser.email, "status" to "invited", "created_at" to sql.ts(now - 22 * hr),
            ),
        )
        // the patient's family (when the earlier parts gave none) — serials can be booked in their names
        if (jdbc.queryForList("SELECT 1 FROM user_family_members WHERE uid = ? LIMIT 1", pu).isEmpty()) {
            sql.insert(
                "user_family_members",
                linkedMapOf("uid" to pu, "member_id" to "m_demo01a", "position" to 0, "name" to "ডেমো মা রাবেয়া বেগম", "relation" to "মা", "age" to 68, "gender" to "female", "phone" to "01200000408"),
            )
            sql.insert(
                "user_family_members",
                linkedMapOf("uid" to pu, "member_id" to "m_demo02b", "position" to 1, "name" to "ডেমো ছেলে তামিম", "relation" to "ছেলে", "age" to 9, "gender" to "male", "phone" to ""),
            )
        }

        // ---------- help & appeals (tickets) ----------
        class M(val from: String, val text: String, val at: Long, val by: String? = null)
        fun ticket(id: String, uid: String, email: String, name: String, category: String, status: String, messages: List<M>) {
            sql.insert(
                "tickets",
                linkedMapOf(
                    "id" to id, "uid" to uid, "email" to email, "name" to name, "category" to category, "status" to status,
                    "last_from" to messages.last().from, "created_at" to sql.ts(messages.first().at), "updated_at" to sql.ts(messages.last().at),
                ),
            )
            messages.forEachIndexed { i, m ->
                sql.insert(
                    "ticket_messages",
                    linkedMapOf("ticket_id" to id, "idx" to i, "sender" to m.from, "text" to m.text, "at" to sql.ts(m.at), "by_email" to m.by),
                )
            }
        }
        val pt = ctx.user("patient")
        // waiting for a reply — the owner answers
        ticket("demo-t-open", pt.uid, pt.email, nameOf("patient"), "appointment", "open", listOf(M("user", "ডা. ডেমো হাসানের কাছে সিরিয়ালের অনুরোধ পাঠিয়েছিলাম, দুই দিন হলো কোনো সাড়া নেই। অনুরোধটা কি পৌঁছেছে? অন্য ডাক্তারের কাছে নতুন করে নেব?", now - 26 * hr)))
        // answered
        ticket(
            "demo-t-answered", pt.uid, pt.email, nameOf("patient"), "blood", "answered",
            listOf(M("user", "রক্তের রিকোয়েস্ট দিয়েছিলাম, কিন্তু ডোনারদের ফোন নম্বর দেখাচ্ছে না কেন?", now - 3 * day), M("admin", "ডোনার \"রক্ত দেব\" চাপার পরই তাঁর নম্বর আপনাকে দেখানো হয় — এটা ডোনারের নিরাপত্তার জন্য। কেউ রাজি হলে সাথে সাথে নোটিফিকেশন পাবেন। (ডেমো উত্তর)", now - 24 * hr, "demo")),
        )
        // closed
        ticket(
            "demo-t-closed", pt.uid, pt.email, nameOf("patient"), "account", "closed",
            listOf(M("user", "প্রোফাইলের ছবি বদলাতে পারছি না, বারবার \"ছবি আপলোড হয়নি\" দেখাচ্ছে।", now - 12 * day), M("admin", "ছবিটা একটু ছোট করে (বা স্ক্রিনশট নিয়ে) আবার চেষ্টা করুন। অ্যাপ হালনাগাদ করে নিন। (ডেমো উত্তর)", now - 11 * day, "demo"), M("user", "হয়েছে, ধন্যবাদ।", now - 11 * day + 3 * hr)),
        )
        // the badge rejection appeal (a background person)
        ticket("demo-t-appeal", trainer, "", "ডেমো ফিটনেস ট্রেইনার মিঠু", "badge", "open", listOf(M("user", "আমার ফিটনেস ট্রেইনার ব্যাজের আবেদন বাতিল হয়েছে (\"সনদের ছবি অস্পষ্ট\")। আমার কাছে পরিষ্কার সনদ আছে — কীভাবে আবার পাঠাব?", now - 18 * hr)))

        // ---------- an old "last donation date correction" request (the admin's "dates" list) ----------
        // the new app no longer creates these (the date can be changed directly), but old ones still show
        sql.insert(
            "correction_requests",
            linkedMapOf(
                "id" to "demo-corr-masud", "uid" to oldDonor, "name" to "ডেমো ডোনার মাসুদ", "current_ms" to today - 40 * day, "requested_ms" to today - 150 * day,
                "reason" to "আসলে আমি ৫ মাস আগে রক্ত দিয়েছিলাম, ভুল করে নতুন তারিখ দিয়ে ফেলেছি।", "status" to "pending", "created_at" to sql.ts(now - 2 * day),
            ),
        )

        // ---------- the bell list ----------
        // id: uid_reverse time_… — newest first (firebase.js addInbox)
        var n = 0
        fun inbox(uid: String, title: String, body: String, data: Map<String, Any?>, at: Long) {
            val rev = (9999999999999L - at).toString().padStart(13, '0')
            sql.insert(
                "inbox_items",
                linkedMapOf(
                    "id" to "${uid}_${rev}_dh${(n++).toString(36)}", "uid" to uid, "title" to title, "body" to body,
                    "data" to PgJson(mapper.writeValueAsString(data)), "created_at" to sql.ts(at),
                ),
            )
        }
        inbox(pu, "আপনার প্রশ্নের উত্তর এসেছে", "ডাক্তারের পরামর্শ ছাড়া ঘুমের ওষুধ খাবেন না — অভ্যাস হয়ে যেতে পারে। রাতে মোবাইল কম দেখুন, প্রতিদিন একই সময়ে ঘুমাতে যান…", mapOf("type" to "answer", "questionId" to "demo-q-sleep"), now - 3 * hr)
        inbox(pu, "Lifolo টিমের উত্তর এসেছে", "\"সাহায্য ও আপিল\" খুলে দেখুন", mapOf("type" to "support", "ticketId" to "demo-t-answered"), now - 24 * hr)
        inbox(pu, "${nameOf("caregiver")} এখন আপনার কেয়ারগিভার", "", mapOf("type" to "care"), now - 6 * day + 2 * hr)
        inbox(pu, "আপনার প্রশ্নের উত্তর এসেছে", "ইফতারে একসাথে বেশি মিষ্টি আর ভাজাপোড়া না খেয়ে খেজুর, পানি, ডাল-সবজি দিয়ে শুরু করুন…", mapOf("type" to "answer", "questionId" to "demo-q-diabetes"), now - 6 * day)
        inbox(pu, "আপনার প্রশ্নের উত্তর এসেছে", "রক্তে শর্করা নিয়ন্ত্রণে থাকলে বেশিরভাগ টাইপ-২ রোগী রোজা রাখতে পারেন…", mapOf("type" to "answer", "questionId" to "demo-q-diabetes"), now - 7 * day)
        // read up to a day and a half ago: the two newest unread (bold, "2" on the bell)
        sql.upsert("user_state", listOf("uid"), linkedMapOf("uid" to pu, "inbox_seen_at" to sql.ts(now - 36 * hr)))

        inbox(ctx.user("donor").uid, "আপনার প্রশ্নের উত্তর এসেছে", "রক্তদানের পর ১–২ দিন হালকা মাথা ঘোরা স্বাভাবিক…", mapOf("type" to "answer", "questionId" to "demo-q-donate"), now - 4 * day)

        val cg = cgUser.uid
        inbox(cg, "ডেমো রোগী আমেনা খাতুন আপনাকে কেয়ারগিভার করতে চান", "Lifolo → প্রোফাইল → কেয়ারগিভার থেকে রাজি হন", mapOf("type" to "care"), now - 22 * hr)
        // the patient's news reaches the caregiver too (careaccess.pushPatient: the patient's name in the title)
        inbox(cg, "${nameOf("patient")}: ${nameOf("doctor")} নতুন প্রেসক্রিপশন দিয়েছেন", "", mapOf("type" to "care", "asUid" to pu), now - 2 * day)
    }
}
