package com.bspc.blood

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/*
 * "আমার কাজ" → "আজকের কাজ" (মালিকের অনুমোদন, ২৪ অক্টোবর ২০২৬; scratchpad/mywork-review/CONTRACT.md §১ আর "Changes"-এর
 * backend-এর চূড়ান্ত নাম): সার্ভারের এক কাজ `work_today {}` — প্রতিটা ব্যবসার অবস্থা (খোলা/বিরতি/বন্ধ), আজকের সংখ্যা,
 * "এখনই দেখুন"-এর কাজ আর শুরু করতে যা বাকি। সার্ভার কাজটা না চিনলে (পুরোনো সার্ভার) null — তখন নিজের ব্যবসার তথ্য
 * (MyWork.summary) থেকে শুধু অবস্থা আর ব্যবসার কার্ড; সংখ্যা আর কাজের তালিকা আসে না (ভুল সংখ্যা দেখানোর চেয়ে না দেখানো ভালো)।
 * ফিচার সুইচ বন্ধ থাকলে সার্ভার সেই সংখ্যা/কাজ পাঠায় না; এখানেও আরেকবার ছাঁকা হয় (WorkGate)।
 */

/** ভূমিকা = হোমের চিপ: সব · চেম্বার · ফার্মেসি · ল্যাব · ব্লাড ব্যাংক · অ্যাম্বুলেন্স */
object WorkRoles {
    const val ALL = "all"
    const val CHAMBER = "chamber"
    val ORDER = listOf(CHAMBER, "pharmacy", "lab", "bloodbank", "ambulance")

    /** সার্ভারের kind → ভূমিকা (ডাক্তার, সহকারী, বাসায় সেবা — সব "চেম্বার") */
    fun of(kind: String): String = when (kind.lowercase()) {
        "chamber", "doctor", "pro", "provider", "homecare", "home_care", "assistant", "rx" -> CHAMBER
        "pathology", "lab" -> "lab"
        "blood_bank", "bloodbank" -> "bloodbank"
        else -> kind.lowercase()
    }

    fun label(role: String): String = when (role) {
        ALL -> tr("All", "সব")
        CHAMBER -> tr("Chamber", "চেম্বার")
        "pharmacy" -> tr("Pharmacy", "ফার্মেসি")
        "lab" -> tr("Lab", "ল্যাব")
        "bloodbank" -> tr("Blood bank", "ব্লাড ব্যাংক")
        "ambulance" -> tr("Ambulance", "অ্যাম্বুলেন্স")
        else -> role
    }

    fun icon(role: String, proType: String? = null): Int = when (role) {
        CHAMBER -> Badges.icon(proType ?: "doctor")
        else -> businessIcon(role)
    }

    /** নিজের কোন কোন ভূমিকা আছে (চিপের ক্রমে) — নিজের তথ্য আর সার্ভারের roles মিলিয়ে (সহকারীর চেম্বারও) */
    fun owned(s: WorkSummary, today: WorkToday?): List<String> {
        val fromToday = (today?.items.orEmpty().map { it.role } + today?.roles.orEmpty().map(::of)).toSet()
        return ORDER.filter { r -> (if (r == CHAMBER) s.professional != null else s.owns(r)) || r in fromToday }
    }
}

data class WorkTask(
    /** ব্যবসার ধরন (ভূমিকার ট্যাগ) */
    val kind: String,
    val id: String,
    val title: String,
    val sub: String = "",
    val at: Long = 0,
    /** accept | ready | report | next | receive | answer | reply | stock … — বোতামের লেখা WorkText.action */
    val action: String = "",
    /** ওয়েবের পথ (/work/…) — অ্যাপে কোন ভাগ খুলবে তা WorkTargets ঠিক করে (ধরন + type দিয়ে) */
    val link: String = "",
    val type: String = "",
    val bizId: String = "",
) {
    val role: String get() = WorkRoles.of(kind)
    val key: String get() = "$kind/$type/$id"
}

data class WorkSetup(val key: String, val done: Boolean)

/** অবস্থার এক সারি: ফার্মেসি/ল্যাব নিজেই, ডাক্তারের এক চেম্বার, এক গাড়ি, এক ব্লাড ব্যাংক */
data class WorkUnit(
    val kind: String,
    val id: String,
    val name: String,
    /** open | paused | closed; null = এই ধরনের অবস্থা নেই (ব্লাড ব্যাংক) */
    val status: String? = null,
    val pausedUntil: Long? = null,
    val chamberIndex: Int? = null,
    /** সহকারী হলে ডাক্তারের uid (chamber_day_set-এ যায়) */
    val doctorUid: String? = null,
    /** অবস্থার পাশের ছোট লেখা (চেম্বারের সময় …) */
    val note: String = "",
    val stockUpdatedAt: Long = 0,
    val stockStale: Boolean = false,
) {
    val role: String get() = WorkRoles.of(kind)
}

/** একটা ব্যবসা (সার্ভারের Biz) */
data class WorkBiz(
    val kind: String,
    val id: String,
    val name: String,
    /** approved | pending | suspended | rejected | null */
    val listingStatus: String? = null,
    /** owner | assistant */
    val access: String = "owner",
    val counts: Map<String, Double> = emptyMap(),
    val tasks: List<WorkTask> = emptyList(),
    val setup: List<WorkSetup> = emptyList(),
    val units: List<WorkUnit> = emptyList(),
) {
    val role: String get() = WorkRoles.of(kind)
}

/** live = সার্ভারের work_today থেকে (false = নিজের তথ্য থেকে বানানো); tasks = সব ব্যবসা মিলিয়ে সবচেয়ে জরুরি (সর্বোচ্চ ৫) */
data class WorkToday(
    val items: List<WorkBiz>,
    val live: Boolean = true,
    val tasks: List<WorkTask> = emptyList(),
    val taskCount: Int = 0,
    val roles: List<String> = emptyList(),
)

object WorkTodayParse {
    private val STATUSES = setOf("open", "paused", "closed")

    private fun JSONObject.s(k: String): String = if (!has(k) || isNull(k)) "" else when (val v = opt(k)) {
        is String -> v
        is Number -> if (v.toDouble() == Math.floor(v.toDouble())) v.toLong().toString() else v.toString()
        else -> v?.toString().orEmpty()
    }

    private fun JSONObject.ms(k: String): Long? {
        if (!has(k) || isNull(k)) return null
        return when (val v = opt(k)) {
            is Number -> v.toLong().takeIf { it > 0 }
            is String -> v.toLongOrNull()?.takeIf { it > 0 }
            else -> null
        }
    }

    private fun JSONObject.status(): String? = s("status").lowercase().takeIf { it in STATUSES }

    private fun objs(a: JSONArray?): List<JSONObject> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }

    /** `{now, date, roles, items, tasks, taskCount}`; শুধু তালিকা এলেও চলে */
    fun parse(o: JSONObject): WorkToday {
        val items = objs(o.optJSONArray("items") ?: o.optJSONArray("businesses")).map(::biz).filter { it.kind.isNotBlank() }
        val tasks = objs(o.optJSONArray("tasks")).mapNotNull { task(it, "") }
        val roles = o.optJSONArray("roles")?.let { r -> (0 until r.length()).map { r.optString(it) }.filter { it.isNotBlank() } }.orEmpty()
        return WorkToday(items, true, tasks, o.optInt("taskCount", tasks.size).coerceAtLeast(tasks.size), roles)
    }

    fun parse(a: JSONArray): WorkToday = parse(JSONObject().put("items", a))

    private fun task(t: JSONObject, kind: String): WorkTask? {
        val title = t.s("title").ifBlank { return null }
        return WorkTask(
            kind = t.s("kind").ifBlank { kind }, id = t.s("id"), title = title, sub = t.s("sub"), at = t.ms("at") ?: 0,
            action = t.s("action"), link = t.s("link"), type = t.s("type"), bizId = t.s("bizId"),
        )
    }

    fun biz(o: JSONObject): WorkBiz {
        val counts = LinkedHashMap<String, Double>()
        o.optJSONObject("counts")?.let { c ->
            c.keys().forEach { k ->
                when (val v = c.opt(k)) {
                    is Number -> counts[k] = v.toDouble()
                    is String -> v.toDoubleOrNull()?.let { counts[k] = it }
                }
            }
        }
        val kind = o.s("kind")
        val id = o.s("id")
        val access = o.s("role").ifBlank { "owner" }
        val listing = (o.s("listingStatus").ifBlank { o.s("listing") }).ifBlank { null }
        val setup = o.optJSONArray("setup")?.let { s ->
            (0 until s.length()).mapNotNull { i -> s.optJSONObject(i)?.let { WorkSetup(it.s("key"), it.optBoolean("done")) } }
        }.orEmpty().filter { it.key.isNotBlank() }
        val units: List<WorkUnit> = when (WorkRoles.of(kind)) {
            WorkRoles.CHAMBER -> objs(o.optJSONArray("chambers")).filter { c ->
                c.optBoolean("sitsToday") || c.optBoolean("closedToday") || c.status() == "paused"
            }.map { c ->
                val idx = if (c.has("chamberIndex") && !c.isNull("chamberIndex")) c.optInt("chamberIndex") else null
                WorkUnit(
                    kind = WorkRoles.CHAMBER, id = "chamber:$id:" + (c.s("chamberKey").ifBlank { idx?.toString().orEmpty() }),
                    name = (if (isBangla) c.s("nameBn") else "").ifBlank { c.s("name") },
                    status = c.status() ?: if (c.optBoolean("closedToday")) "closed" else "open",
                    pausedUntil = c.ms("pausedUntil"), chamberIndex = idx,
                    doctorUid = if (access == "assistant") id else null,
                )
            }
            "ambulance" -> objs(o.optJSONArray("vehicles")).filter { v -> v.s("listingStatus").let { it.isBlank() || it == "approved" } }.map { v ->
                WorkUnit(kind = "ambulance", id = v.s("id"), name = v.s("name"), status = if (v.optBoolean("available")) "open" else "closed")
            }
            "bloodbank" -> if (listing == null || listing == "approved") listOf(
                WorkUnit(kind = "bloodbank", id = id, name = o.s("name"), stockUpdatedAt = o.ms("stockUpdatedAt") ?: 0, stockStale = o.optBoolean("stockStale")),
            ) else emptyList()
            "pharmacy", "lab" -> if (listing == null || listing == "approved") listOfNotNull(
                o.status()?.let { st -> WorkUnit(kind = kind, id = id, name = o.s("name"), status = st, pausedUntil = o.ms("pausedUntil").takeIf { st == "paused" }) },
            ) else emptyList()
            else -> emptyList()
        }
        return WorkBiz(
            kind = kind, id = id, name = o.s("name"), listingStatus = listing, access = access,
            counts = counts, tasks = objs(o.optJSONArray("tasks")).mapNotNull { task(it, kind) }, setup = setup, units = units,
        )
    }
}

/** ফিচার সুইচ বন্ধ → সেই সুবিধার সংখ্যা/কাজ লুকানো (সার্ভার বাদ দিলেও আরেকবার) */
object WorkGate {
    private fun invKind(role: String) = if (role == WorkRoles.CHAMBER) "chamber" else role

    /** চাবি বা কাজের নাম কোন সুইচের উপর নির্ভর করে — null = কোনোটার উপর নয় */
    fun switchFor(role: String, key: String): String? {
        val k = key.lowercase()
        return when {
            "invoice" in k || "receipt" in k -> InvoiceFeatures.key(invKind(role))
            "online" in k || "video" in k -> Features.ONLINE_CHAMBER
            "pay" in k -> Features.PAY_MANUAL
            "chat" in k || "message" in k -> Features.CHAT
            else -> null
        }
    }

    fun allows(role: String, key: String): Boolean = switchFor(role, key)?.let { Features.on(it) } ?: true

    // link = ওয়েবের পথ — অ্যাপ ধরন + type + id দিয়ে চেনে (backend/docs/MYWORK.md), link দিয়ে নয়
    fun task(t: WorkTask): Boolean = allows(t.role, t.action) && allows(t.role, t.type)

    fun filter(b: WorkBiz): WorkBiz = b.copy(counts = b.counts.filterKeys { allows(b.role, it) }, tasks = b.tasks.filter(::task))

    fun filter(t: WorkToday): WorkToday = t.copy(items = t.items.map(::filter), tasks = t.tasks.filter(::task))
}

/** লেখা (ওয়েবের হোমের একই শব্দ) */
object WorkText {
    fun title(): String = tr("Today's work", "আজকের কাজ")
    fun join(): String = tr("Give service", "সেবা দিন")
    fun tab(): String = tr("My work", "আমার কাজ")
    fun open(): String = tr("Open", "খোলা")
    fun paused(): String = tr("Break", "বিরতি")
    fun closed(): String = tr("Closed", "বন্ধ")
    fun openNow(): String = tr("Open now", "এখন খোলা")
    fun reopensAt(at: Long): String = tr("Opens again at ", "আবার খুলবে ") + Rules.formatTime(at)
    fun free(): String = tr("I am free", "খালি আছি")
    fun onTrip(): String = tr("On a trip", "ট্রিপে আছি")
    fun serialToday(): String = tr("Serial running today", "আজ সিরিয়াল চলছে")
    fun closedToday(): String = tr("Closed today", "আজ বন্ধ")
    fun noChamberToday(): String = tr("No chamber today", "আজ কোনো চেম্বার নেই")
    fun pauseFor(): String = tr("Break for how long?", "কতক্ষণের বিরতি?")
    fun min30(): String = tr("30 minutes", "৩০ মিনিট")
    fun hour1(): String = tr("1 hour", "১ ঘণ্টা")
    fun restOfDay(): String = tr("For today", "আজকের মতো")
    fun cancel(): String = tr("Cancel", "বাতিল")
    fun now(): String = tr("Needs you now", "এখনই দেখুন")
    fun nothingNow(): String = tr("Nothing is waiting right now", "এখন কোনো কাজ বাকি নেই")
    fun seeAll(n: Int): String = tr("See all (${num(n)})", "সব দেখুন (${num(n)})")
    fun showLess(): String = tr("Show less", "কম দেখুন")
    fun setupTitle(): String = tr("Left to start", "শুরু করতে যা বাকি")
    fun preview(): String = ProviderText.preview()
    fun see(): String = tr("See", "দেখুন")
    fun openPage(): String = tr("Open", "খুলুন")
    fun businesses(): String = tr("Your services", "আপনার সেবা")
    fun underReview(): String = tr("Under review — patients can't see it yet", "যাচাই চলছে — রোগীরা এখনো দেখতে পান না")
    fun stockGive(): String = tr("Update stock", "স্টক দিন")
    fun stockNever(): String = tr("Stock not given yet", "স্টকের খবর এখনো দেওয়া হয়নি")
    fun retry(): String = tr("Try again", "আবার চেষ্টা করুন")
    fun loadFailed(): String = tr("Couldn't load your work", "আপনার কাজ লোড হয়নি")
    fun pending(): String = tr("Under review", "যাচাই চলছে")

    fun action(a: String): String = when (a.lowercase()) {
        "accept" -> tr("Accept", "গ্রহণ করুন")
        "ready" -> tr("Ready", "তৈরি")
        "report" -> tr("Send report", "রিপোর্ট পাঠান")
        "next", "call" -> tr("Call", "ডাকুন")
        "stock" -> stockGive()
        "receive" -> tr("Sample taken", "নমুনা নিয়েছি")
        "answer", "reply" -> tr("Reply", "উত্তর দিন")
        else -> tr("Open", "খুলুন")
    }

    /** আজকের সংখ্যার নাম (backend-এর চাবি) — জানা নামের বাইরের চাবি দেখানো হয় না (null) */
    fun count(role: String, key: String): String? = when (role) {
        WorkRoles.CHAMBER -> when (key) {
            "today" -> tr("Today's serial", "আজকের সিরিয়াল")
            "waiting" -> tr("Waiting", "অপেক্ষায়")
            "requests" -> tr("New requests", "নতুন অনুরোধ")
            else -> null
        }
        "pharmacy" -> when (key) {
            "new" -> tr("New orders", "নতুন অর্ডার")
            "active" -> tr("In progress", "চলছে")
            "salesTodayTk" -> tr("Today's sales", "আজকের বিক্রি")
            "finds" -> tr("Medicine asks", "ওষুধ খোঁজা")
            else -> null
        }
        "lab" -> when (key) {
            "new" -> tr("New requests", "নতুন অনুরোধ")
            "active" -> tr("Reports due", "রিপোর্ট বাকি")
            "reported" -> tr("Reported", "রিপোর্ট দেওয়া")
            else -> null
        }
        "ambulance" -> when (key) {
            "calls" -> tr("New calls", "নতুন ডাক")
            "trips" -> tr("On a trip", "ট্রিপে")
            else -> null
        }
        else -> null
    }

    fun isMoney(key: String): Boolean = key.endsWith("Tk") || key.lowercase().let { "taka" in it || "amount" in it }

    fun countValue(key: String, v: Double): String = if (isMoney(key)) PharmacyBooks.taka(v) else num(if (v == Math.floor(v)) v.toLong().toString() else v.toString())

    fun setup(key: String): String = when (key) {
        "photo" -> tr("Photo", "ছবি")
        "hours", "time" -> tr("Hours", "সময়")
        "address" -> tr("Address", "ঠিকানা")
        "location" -> tr("Location on map", "জায়গা")
        "fee" -> tr("Fee", "ফি")
        "chamber" -> tr("Chamber", "চেম্বার")
        "docs" -> tr("Papers", "কাগজের ছবি")
        "prices" -> tr("Test prices", "পরীক্ষার দাম")
        "stock" -> tr("Blood stock", "রক্তের মজুদ")
        "approved" -> tr("Approval", "অনুমোদন")
        "base" -> tr("Where the vehicle waits", "গাড়ি কোথায় থাকে")
        "homeVisit" -> tr("Home visit details", "বাসায় সেবার তথ্য")
        else -> key
    }
}

/** খোলা/বিরতি/বন্ধ বদলানো — backend-এর চূড়ান্ত কাজ (CONTRACT.md "Changes", ২৪ অক্টোবর) */
object WorkStatusApi {
    /** সার্ভার বিরতি চেনে না (এই ধরনে) — "বিরতি" লুকানো */
    private val noPause = androidx.compose.runtime.mutableStateListOf<String>()

    /** "বিরতি": ফার্মেসি, ল্যাব, চেম্বার — শুধু নতুন সার্ভারে (work_today আছে); অ্যাম্বুলেন্সে নেই */
    fun canPause(role: String, live: Boolean): Boolean =
        live && role in setOf("pharmacy", "lab", WorkRoles.CHAMBER) && role !in noPause

    fun forgetUnknown() = noPause.clear()

    /** "আজকের মতো" = ঢাকার আজকের মাঝরাত পর্যন্ত */
    fun endOfDay(now: Long): Long {
        val day = 24L * 60 * 60 * 1000
        return Math.floorDiv(now + Rules.DHAKA_OFFSET_MS, day) * day + day - Rules.DHAKA_OFFSET_MS
    }

    private suspend fun call(action: String, body: JSONObject): JSONObject = ServerApi.post("/api/community", body.put("action", action))

    /** খোলা/বন্ধ (ফার্মেসি/ল্যাব): open null = সময় অনুযায়ী; তবু বন্ধ থাকলে (সময়ের বাইরে) open true */
    private suspend fun openOverride(action: String, status: String, until: Long?, wasPaused: Boolean, kind: String) {
        when (status) {
            "paused" -> call(action, JSONObject().put("open", false).apply { if (until != null) put("until", until) })
            "closed" -> call(action, JSONObject().put("open", false))
            else -> {
                // পুরোনো "কয়েক দিন বন্ধ" (pharmacy_pause) থাকলে আগে সেটা শেষ
                if (wasPaused && kind == "pharmacy") runCatching { Pharmacies.pause(null) }
                val r = call(action, JSONObject().put("open", JSONObject.NULL))
                if (r.has("openNow") && !r.optBoolean("openNow")) call(action, JSONObject().put("open", true))
            }
        }
    }

    /**
     * status: open | paused | closed; until: বিরতির শেষ (ms) — "আজকের মতো"-তে ফার্মেসি/ল্যাবে null (পরের নির্ধারিত বদল পর্যন্ত),
     * চেম্বারে আজকের মাঝরাত।
     */
    suspend fun set(u: WorkUnit, status: String, until: Long?) {
        try {
            when (u.role) {
                "pharmacy" -> openOverride("pharmacy_open_override", status, until, u.status == "paused", "pharmacy")
                "lab" -> openOverride("lab_open_override", status, until, u.status == "paused", "lab")
                WorkRoles.CHAMBER -> {
                    val idx = u.chamberIndex ?: return
                    val body = JSONObject().put("chamberIndex", idx).put("closed", status != "open").apply {
                        u.doctorUid?.let { put("doctorUid", it) }
                        if (status == "paused") put("until", until ?: endOfDay(System.currentTimeMillis()))
                    }
                    call("chamber_day_set", body)
                }
                "ambulance" -> Ambulances.setAvailable(u.id, status == "open")
            }
        } catch (e: Throwable) {
            if (status == "paused" && ProviderSwitches.unknown(e)) noPause.add(u.role)
            throw e
        }
    }

    /** `work_today {}`; সার্ভার কাজটা চেনে না (পুরোনো সার্ভার) → null। ইন্টারনেট/অন্য ভুল হলে throw */
    suspend fun today(): WorkToday? {
        val r = try {
            call("work_today", JSONObject())
        } catch (e: ServerError) {
            if (ProviderSwitches.unknown(e) || Features.isFeatureOff(e)) return null
            throw e
        }
        return WorkTodayParse.parse(r)
    }
}

/** সার্ভারে work_today না থাকলে: নিজের ব্যবসার তথ্য থেকে শুধু অবস্থা (সংখ্যা/কাজ নয়) */
object WorkFallback {
    fun build(s: WorkSummary, provider: Provider?, now: Long = System.currentTimeMillis()): WorkToday {
        val out = ArrayList<WorkBiz>()
        s.professional?.let { type ->
            val units = if (type == "nurse") emptyList() else provider?.chambers.orEmpty().mapIndexedNotNull { i, c ->
                val st = ChamberClock.state(c, now)
                if (!st.good && !c.closedToday) null
                else WorkUnit(
                    kind = WorkRoles.CHAMBER, id = "chamber:me:$i", name = ChamberClock.shortName(c),
                    status = if (c.closedToday) "closed" else "open", chamberIndex = i, note = if (c.closedToday) "" else st.text,
                )
            }
            out += WorkBiz(if (type == "nurse") "homecare" else "doctor", "me", "", "approved", units = units)
        }
        s.pharmacy?.let { p ->
            val pausedNow = p.closedUntil > now
            val st = when {
                pausedNow && p.closedUntil - now <= 24L * 3_600_000 -> "paused"
                pausedNow -> "closed"
                pharmacyOpen(p) == false -> "closed"
                else -> "open"
            }
            out += WorkBiz(
                "pharmacy", p.id, p.name, p.status,
                units = if (p.status == "approved") listOf(WorkUnit("pharmacy", p.id, p.name, st, p.closedUntil.takeIf { st == "paused" })) else emptyList(),
            )
        }
        s.lab?.let { l ->
            out += WorkBiz(
                "lab", l.id, l.name, l.status,
                units = if (l.status == "approved") listOf(WorkUnit("lab", l.id, l.name, if (l.extras.openNow == false) "closed" else "open")) else emptyList(),
            )
        }
        s.bloodBanks.forEach { b ->
            out += WorkBiz(
                "bloodbank", b.id, b.name, b.status,
                units = if (b.status == "approved") listOf(WorkUnit("bloodbank", b.id, b.name, stockUpdatedAt = b.extras.stockUpdatedAt, stockStale = b.extras.stockStale(now))) else emptyList(),
            )
        }
        if (s.ambulances.isNotEmpty()) {
            out += WorkBiz(
                "ambulance", "me", businessName("ambulance"),
                if (s.ambulances.any { it.status == "approved" }) "approved" else s.ambulances.first().status,
                units = s.ambulances.filter { it.status == "approved" }.map { a -> WorkUnit("ambulance", a.id, a.name, if (a.available) "open" else "closed") },
            )
        }
        return WorkToday(out, live = false)
    }

    /** সার্ভারের তথ্যে যে ভূমিকা নেই সেটা নিজের তথ্য থেকে (সুইচ বন্ধের ছাঁকনিও এখানে) */
    fun merge(live: WorkToday?, fallback: WorkToday): WorkToday {
        if (live == null) return fallback
        val roles = live.items.map { it.role }.toSet()
        val g = WorkGate.filter(live)
        return g.copy(items = g.items + fallback.items.filter { it.role !in roles })
    }
}

/** এক ভূমিকার সব কিছু একসাথে (হোমের এক ঘর) */
data class WorkBlock(val role: String, val items: List<WorkBiz>) {
    val units: List<WorkUnit> get() = items.flatMap { it.units }

    /** সংখ্যা (একই চাবি যোগ করে), জানা নামেরগুলো, সর্বোচ্চ ৪টা */
    fun tiles(): List<Pair<String, Double>> {
        val sum = LinkedHashMap<String, Double>()
        items.forEach { b -> b.counts.forEach { (k, v) -> if (WorkText.count(role, k) != null) sum[k] = (sum[k] ?: 0.0) + v } }
        return sum.entries.map { it.key to it.value }.take(4)
    }

    /** সব হয়ে গেলে খালি (তালিকা লুকানো) */
    fun setupLeft(): List<WorkSetup> = items.flatMap { it.setup }.distinctBy { it.key }.let { all -> if (all.all { it.done }) emptyList() else all }

    fun tasks(): List<WorkTask> = items.flatMap { it.tasks }

    /** যাচাই চলছে / বাতিল / বন্ধ করা — অনুমোদিত কিছুই নেই */
    val pending: Boolean get() = items.isNotEmpty() && items.none { it.listingStatus == null || it.listingStatus == "approved" }
}

fun workBlocks(today: WorkToday, roles: List<String>): List<WorkBlock> = roles.map { r -> WorkBlock(r, today.items.filter { it.role == r }) }

/**
 * "এখনই দেখুন": "সব"-এ সার্ভারের জরুরি-আগে তালিকা (top) আর মোট সংখ্যা; একটা ভূমিকায় সেই ভূমিকার কাজ (সার্ভারের ক্রমে)।
 * পুরো তালিকা (সব দেখুন) = top আগে, তারপর বাকিগুলো — একই কাজ দুবার নয়।
 */
data class WorkTaskList(val top: List<WorkTask>, val all: List<WorkTask>, val total: Int)

fun workTasks(today: WorkToday, blocks: List<WorkBlock>, allRoles: Boolean): WorkTaskList {
    val roles = blocks.map { it.role }.toSet()
    val perBiz = blocks.flatMap { it.tasks() }
    val top = (if (allRoles && today.tasks.isNotEmpty()) today.tasks else perBiz).filter { it.role in roles && WorkGate.task(it) }
    val all = (top + perBiz).distinctBy { it.key }
    val total = if (allRoles && today.tasks.isNotEmpty()) maxOf(today.taskCount, all.size) else all.size
    return WorkTaskList(top.take(5), all, total)
}

/** কোন ভাগ খুলবে: ফার্মেসি/ল্যাব "orders:<ভাগ>" / "sales" / "finds"; বাকিরা শুধু পাতা (WorkNav.target, MyWorkTabs.kt) */
object WorkTargets {
    fun forCount(role: String, key: String): String? = when (role) {
        "pharmacy" -> when (key) {
            "salesTodayTk", "salesToday" -> "sales"
            "finds" -> "finds"
            "active" -> "orders:active"
            else -> "orders:new"
        }
        "lab" -> when (key) {
            "active" -> "orders:active"
            "reported" -> "orders:reported"
            else -> "orders:new"
        }
        else -> null
    }

    fun forTask(t: WorkTask): String? = when (t.role) {
        "pharmacy" -> if (t.type == "find" || t.action == "answer") "finds" else "orders:new"
        "lab" -> if (t.type == "lab_report" || t.action == "report") "orders:active" else "orders:new"
        else -> null
    }
}

/** এই ফোনে, ব্যবহারকারী ধরে: কোন চিপ বাছা ছিল; "আমার কাজ" আছে কি না (ট্যাবের নাম আগে থেকেই ঠিক রাখতে) */
object WorkHomePrefs {
    private const val FILE = "work_home"
    private fun uid() = AuthRepository.currentUid ?: LoginGate.uid.value ?: "guest"

    fun role(context: Context): String =
        runCatching { context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(uid() + "_role", null) }.getOrNull() ?: WorkRoles.ALL

    fun setRole(context: Context, role: String) {
        runCatching { context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(uid() + "_role", role).apply() }
    }

    /** null = আগে কখনো জানা যায়নি */
    fun cachedHas(context: Context): Boolean? = runCatching {
        val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        if (p.contains(uid() + "_has")) p.getBoolean(uid() + "_has", false) else null
    }.getOrNull()

    fun setHas(context: Context, has: Boolean) {
        runCatching { context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(uid() + "_has", has).apply() }
    }
}
