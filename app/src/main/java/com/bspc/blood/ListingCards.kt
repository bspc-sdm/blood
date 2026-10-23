package com.bspc.blood

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

// ------------------------------------------------------------
// পাঁচ ধরনের ঘর → তালিকার কার্ড আর বিস্তারিত (ListingCardData / ListingDetailData)।
// সবার তালিকা আর "আমার কাজ"-এর "রোগীরা আপনাকে যেভাবে দেখেন" — দুই জায়গায় এই একই হিসাব (একই কার্ড)।
// ------------------------------------------------------------

/** রেটিং: নতুন ratingSummary, না এলে আগের rating + ratingCount */
private fun ratingOf(x: ListingExtras, oldAvg: Double, oldCount: Int): Pair<Double, Int>? =
    x.ratingAvg?.let { it to x.ratingCount } ?: if (oldCount > 0) oldAvg to oldCount else null

private fun ratingText(r: Pair<Double, Int>?): String? = r?.let { Listing.rating(it.first, it.second) }

private fun stockNoteOf(x: ListingExtras): ListingBadge? {
    val stale = x.stockStale()
    return Listing.stockNote(x.stockUpdatedAt, stale)?.let { ListingBadge(it, if (stale) BadgeTone.WARN else BadgeTone.NEUTRAL) }
}

// ---------------- অ্যাম্বুলেন্স ----------------

fun ambulanceDistance(a: Ambulance): String? = Listing.km(a.extras.distanceKm, a.extras.distanceApprox) ?: ambulanceKm(a)
fun ambulanceRating(a: Ambulance): Pair<Double, Int>? = ratingOf(a.extras, a.rating, a.ratingCount)

fun ambulanceBadge(a: Ambulance): ListingBadge = when {
    a.busySince > 0 -> ListingBadge(ListingText.onTrip(), BadgeTone.NEUTRAL)
    a.available -> ListingBadge(ListingText.available(), BadgeTone.GOOD)
    else -> ListingBadge(ListingText.callToCheck(), BadgeTone.NEUTRAL)
}

/** মূল লাইন: ধরন · দূরত্ব · রেটিং */
fun ambulanceKeyLine(a: Ambulance): String =
    Listing.keyLine(a.types.joinToString(", ") { AmbulanceTypes.label(it) }, ambulanceDistance(a), ratingText(ambulanceRating(a)))

fun ambulanceCard(a: Ambulance, onOpen: () -> Unit, onCall: () -> Unit, onReport: (() -> Unit)?): ListingCardData = ListingCardData(
    id = a.id, type = ListingType.AMBULANCE, name = a.name, icon = R.drawable.ic_ambulance,
    keyLine = ambulanceKeyLine(a), onOpen = onOpen, verified = a.extras.verified,
    badges = listOf(ambulanceBadge(a)),
    // ফোনই মূল কাজ — পাশে আবার ফোনের আইকন নয়
    primary = CardAction(ListingText.call(), R.drawable.ic_call, onCall),
    onReport = onReport,
)

private fun ambulanceStatusLine(a: Ambulance): String = when {
    a.busySince > 0 -> tr("On a trip since ", "ট্রিপে গেছে ") + timeAgo(a.busySince)
    a.available && a.availableAt > 0 -> tr("Marked available ", "খালি জানিয়েছেন ") + timeAgo(a.availableAt)
    a.available -> ListingText.available()
    else -> tr("Call to check if it is free", "খালি আছে কিনা ফোনে জেনে নিন")
}

/** onRequest: "অ্যাম্বুলেন্স ডাকুন" (ট্রিপে থাকা গাড়িতে নয়) — তখন ফোন পাশের আইকনে */
fun ambulanceDetail(a: Ambulance, onCall: () -> Unit, onRequest: (() -> Unit)?, onRate: (() -> Unit)?, onReport: (() -> Unit)?): ListingDetailData {
    val request = onRequest?.takeIf { a.busySince == 0L }
    return ListingDetailData(
        type = ListingType.AMBULANCE, name = a.name, icon = R.drawable.ic_ambulance, keyLine = ambulanceKeyLine(a), verified = a.extras.verified,
        badges = listOf(ambulanceBadge(a)),
        facts = listOf(
            R.drawable.ic_schedule to ambulanceStatusLine(a),
            R.drawable.ic_payments to (a.fareChart?.summary()?.let { tr("Fare: ", "ভাড়া: ") + it } ?: ""),
        ),
        sections = listOf(
            DetailSection(tr("Vehicle", "গাড়ি"), a.types.map { R.drawable.ic_ambulance to AmbulanceTypes.label(it) }),
            DetailSection(tr("Fare", "ভাড়া"), listOf(R.drawable.ic_edit_note to a.fare)),
        ),
        address = listOf(R.drawable.ic_location_on to listOf(a.area, Areas.label(a.district, a.upazilaId)).filter { it.isNotBlank() }.joinToString(", ")),
        phones = listOf(a.phone),
        ratingText = ratingText(ambulanceRating(a)), onRate = onRate,
        primary = if (request != null) CardAction(tr("Call ambulance", "অ্যাম্বুলেন্স ডাকুন"), R.drawable.ic_ambulance, request) else CardAction(ListingText.call(), R.drawable.ic_call, onCall),
        contact = if (request != null) ListingContact(a.phone, onPhone = onCall) else null,
        onReport = onReport,
    )
}

// ---------------- ফার্মেসি ----------------

fun pharmacyOpen(p: Pharmacy): Boolean? = if (p.closedUntil > 0) false else p.extras.openNow ?: p.openNow
fun pharmacyDistance(p: Pharmacy): String? = Listing.km(p.extras.distanceKm ?: p.km, p.extras.distanceApprox)
fun pharmacyRating(p: Pharmacy): Pair<Double, Int>? = ratingOf(p.extras, p.rating, p.ratingCount)
fun pharmacyDelivers(p: Pharmacy): Boolean = p.extras.delivery ?: (p.delivery || p.online)

fun pharmacyKeyLine(p: Pharmacy): String =
    Listing.keyLine(pharmacyOpen(p)?.let { ListingText.open(it) }, pharmacyDistance(p), ListingText.delivery(pharmacyDelivers(p)))

fun pharmacyBadges(p: Pharmacy): List<ListingBadge> = listOfNotNull(
    if (p.extras.is24h ?: p.open24) ListingBadge(ListingText.hours24(), BadgeTone.INFO) else null,
    if (p.discount > 0) ListingBadge(ListingText.discount(p.discount), BadgeTone.INFO) else null,
)

/** onSend: "প্রেসক্রিপশন পাঠান" — বন্ধ দোকানে দেখায় না */
fun pharmacyCard(p: Pharmacy, onOpen: () -> Unit, onSend: () -> Unit, onPhone: () -> Unit, onReport: (() -> Unit)?): ListingCardData = ListingCardData(
    id = p.id, type = ListingType.PHARMACY, name = p.name, icon = R.drawable.ic_storefront,
    keyLine = pharmacyKeyLine(p), onOpen = onOpen, verified = p.extras.verified,
    ratingText = ratingText(pharmacyRating(p)),
    badges = pharmacyBadges(p),
    primary = if (pharmacyOpen(p) != false) CardAction(ListingText.sendRx(), R.drawable.ic_send, onSend) else null,
    contact = ListingContact(p.phone, onPhone = onPhone),
    onReport = onReport,
)

fun pharmacyDetail(p: Pharmacy, online: Boolean, onSend: () -> Unit, onPhone: () -> Unit, onRate: (() -> Unit)?, onReport: (() -> Unit)?): ListingDetailData = ListingDetailData(
    type = ListingType.PHARMACY, name = p.name, icon = R.drawable.ic_storefront, keyLine = pharmacyKeyLine(p), verified = p.extras.verified,
    badges = pharmacyBadges(p),
    facts = listOf(
        R.drawable.ic_payments to PharmacyText.flatDiscount(p).ifBlank { tr("No discount", "ছাড় নেই") },
        R.drawable.ic_info to PharmacyText.terms(p),
        R.drawable.ic_local_shipping to PharmacyText.delivery(p, online).ifBlank { tr("No home delivery", "বাসায় ডেলিভারি নেই") },
    ),
    sections = listOf(
        DetailSection(
            tr("Open hours", "খোলার সময়"),
            listOf(
                R.drawable.ic_schedule to if (p.closedUntil > 0) tr("Closed until ", "বন্ধ থাকবে ") + Rules.formatDate(p.closedUntil) + tr("", " পর্যন্ত") else p.hoursText,
                R.drawable.ic_calendar_month to closedDaysText(p.closedDays),
            ),
        ),
    ),
    address = listOf(R.drawable.ic_location_on to listOf(p.address, Areas.label(p.district, p.upazilaId)).filter { it.isNotBlank() }.joinToString(", ")),
    phones = listOf(p.phone),
    ratingText = ratingText(pharmacyRating(p)), ratingExtra = pharmacyAspectsText(p.aspects), onRate = onRate,
    primary = if (pharmacyOpen(p) != false) CardAction(ListingText.sendRx(), R.drawable.ic_send, onSend) else null,
    contact = ListingContact(p.phone, onPhone = onPhone),
    onReport = onReport,
)

// ---------------- ব্লাড ব্যাংক ----------------

fun bloodBankOpen(b: BloodBank): Boolean? = b.extras.openNow ?: b.openNow

fun bloodBankKeyLine(b: BloodBank): String =
    Listing.keyLine(Listing.km(b.extras.distanceKm, b.extras.distanceApprox), bloodBankOpen(b)?.let { ListingText.open(it) })

fun bloodBankCard(b: BloodBank, onOpen: () -> Unit, onCall: () -> Unit, onReport: (() -> Unit)?): ListingCardData = ListingCardData(
    id = b.id, type = ListingType.BLOODBANK, name = b.name, icon = R.drawable.ic_bloodtype, danger = true,
    keyLine = bloodBankKeyLine(b), onOpen = onOpen, verified = b.extras.verified,
    badges = listOfNotNull(
        ListingBadge(BloodBankInfo.kind(b.kind), BadgeTone.NEUTRAL),
        if (b.extras.is24h ?: b.open24) ListingBadge(ListingText.hours24(), BadgeTone.INFO) else null,
    ),
    stock = b.extras.stock, stockNote = stockNoteOf(b.extras),
    primary = CardAction(ListingText.call(), R.drawable.ic_call, onCall),
    onReport = onReport,
)

fun bloodBankDetail(b: BloodBank, onCall: () -> Unit, onReport: (() -> Unit)?): ListingDetailData = ListingDetailData(
    type = ListingType.BLOODBANK, name = b.name, icon = R.drawable.ic_bloodtype, danger = true, keyLine = bloodBankKeyLine(b), verified = b.extras.verified,
    badges = listOf(ListingBadge(BloodBankInfo.kind(b.kind), BadgeTone.NEUTRAL)),
    stock = b.extras.stock, stockNote = stockNoteOf(b.extras),
    facts = listOf(
        R.drawable.ic_schedule to listOf(b.hoursText, closedDaysText(b.closedDays)).filter { it.isNotBlank() }.joinToString(" · "),
        R.drawable.ic_info to tr("Buying or selling blood is illegal; only processing costs apply.", "রক্ত কেনা-বেচা অবৈধ; শুধু পরীক্ষা ও প্রসেসিং খরচ লাগে।"),
    ),
    sections = listOf(DetailSection(tr("Available here", "যা পাওয়া যায়"), b.services.map { R.drawable.ic_bloodtype to BloodBankInfo.service(it) })),
    address = listOf(R.drawable.ic_location_on to listOf(b.address, Areas.label(b.district, b.upazilaId)).filter { it.isNotBlank() }.joinToString(", ")),
    phones = listOf(b.phone),
    // ব্লাড ব্যাংকের রেটিং নেই
    showRatings = false,
    primary = CardAction(ListingText.call(), R.drawable.ic_call, onCall),
    onReport = onReport,
)

// ---------------- ল্যাব ----------------

fun labHome(l: Lab): Boolean = l.extras.homeCollection ?: l.homeCollection

fun labKeyLine(l: Lab): String = Listing.keyLine(
    l.extras.openNow?.let { ListingText.open(it) },
    Listing.km(l.extras.distanceKm, l.extras.distanceApprox),
    if (labHome(l)) ListingText.homeSample() else null,
)

fun labCard(l: Lab, onOpen: () -> Unit, onSend: () -> Unit, onPhone: (() -> Unit)?, onReport: (() -> Unit)?): ListingCardData = ListingCardData(
    id = l.id, type = ListingType.LAB, name = l.name, icon = R.drawable.ic_local_hospital,
    keyLine = labKeyLine(l), onOpen = onOpen, verified = l.extras.verified,
    ratingText = ratingText(l.extras.ratingAvg?.let { it to l.extras.ratingCount }),
    primary = CardAction(ListingText.sendTests(), R.drawable.ic_send, onSend),
    contact = ListingContact(l.phone, onPhone = onPhone),
    onReport = onReport,
)

/** full: পুরো দামের তালিকা (null = আসছে; Result-এর ভেতরে null = তালিকা নেই) */
fun labDetail(l: Lab, full: Result<LabPrices?>?, onSend: () -> Unit, onPhone: (() -> Unit)?, onReport: (() -> Unit)?): ListingDetailData {
    val p = l.prices
    val list = full?.getOrNull()
    return ListingDetailData(
        type = ListingType.LAB, name = l.name, icon = R.drawable.ic_local_hospital, keyLine = labKeyLine(l), verified = l.extras.verified,
        facts = listOf(
            R.drawable.ic_schedule to l.hours,
            R.drawable.ic_home_health to if (labHome(l)) tr("Collects samples from home", "বাসা থেকে নমুনা নেয়") + (p?.let { LabPriceText.homeFee(it.homeFee).let { f -> if (f.isBlank()) "" else " · $f" } } ?: "") else "",
            R.drawable.ic_payments to (p?.let { LabPriceText.discount(it.discountPct) } ?: ""),
        ),
        sections = listOf(
            DetailSection(
                tr("Tests & prices", "পরীক্ষা ও দাম"),
                if (p != null) listOf(
                    R.drawable.ic_sync to if (full == null) tr("Loading the price list…", "দামের তালিকা আসছে…") else "",
                    R.drawable.ic_warning to (full?.exceptionOrNull()?.message ?: ""),
                ) else listOf(R.drawable.ic_call to LabPriceText.noPrice()),
                rows = list?.items?.map { DetailRow(it.name, LabPriceText.taka(it.price), if (it.tat.isNotBlank()) tr("Report: ", "রিপোর্ট: ") + it.tat else "") }.orEmpty(),
            ),
        ),
        address = listOf(R.drawable.ic_location_on to listOf(l.address, Areas.label(l.district, l.upazilaId)).filter { it.isNotBlank() }.joinToString(", ")),
        phones = listOf(l.phone),
        ratingText = ratingText(l.extras.ratingAvg?.let { it to l.extras.ratingCount }),
        showRatings = l.extras.ratingAvg != null,
        primary = CardAction(ListingText.sendTests(), R.drawable.ic_send, onSend),
        contact = ListingContact(l.phone, onPhone = onPhone),
        onReport = onReport,
    )
}

/** ল্যাবের বিস্তারিত: খুললে তবেই পুরো দামের তালিকা আনা (lab_prices_public) */
@Composable
fun LabDetailScreen(l: Lab, onSend: () -> Unit, onDismiss: () -> Unit, onReport: (() -> Unit)? = null, preview: Boolean = false) {
    var full by remember { mutableStateOf<Result<LabPrices?>?>(null) }
    LaunchedEffect(l.id) {
        if (l.prices == null) { full = Result.success(null); return@LaunchedEffect }
        full = runCatching { RxNet.labPricesPublic(l.id) }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
    }
    ListingDetail(labDetail(l, full, if (preview) ({}) else onSend, null, onReport), onDismiss)
}

// ---------------- ডাক্তার ----------------


/** তালিকায় কোন চেম্বার: ছাঁকনিতে মেলা (matched), নইলে এলাকার সাথে মেলা, নইলে প্রথমটা */
fun providerChamber(p: Provider, filter: CareFilter): Chamber? =
    (if (filter.online) p.chambers.firstOrNull { it.online } else null)
        ?: p.matched?.firstOrNull()?.let { p.chambers.getOrNull(it) }
        ?: p.extras.nextSlot?.chamberIndex?.let { p.chambers.getOrNull(it) }?.takeIf { filter.hospital == null }
        ?: p.chambers.firstOrNull { c ->
            (filter.hospital == null || c.hospitalId == filter.hospital.id) &&
                (filter.district == null || c.district == filter.district) &&
                (filter.upazilaId == null || c.upazilaId == filter.upazilaId)
        }
        ?: p.chambers.firstOrNull()

/** সার্ভারের nextSlot, না এলে চেম্বারের সময় থেকে (অ্যাপে অ্যাপয়েন্টমেন্ট নেন এমন চেম্বার; "আজ বন্ধ" দেওয়া থাকলে আজ নয়) */
fun providerNextSlot(p: Provider, now: Long = System.currentTimeMillis()): NextSlot? {
    p.extras.nextSlot?.let { return it }
    if (!p.canBook) return null
    val t0 = Math.floorDiv(now + Rules.DHAKA_OFFSET_MS, DAY_MS) * DAY_MS
    var best: Pair<Long, Int>? = null
    p.chambers.forEachIndexed { i, c ->
        if (!c.appointments) return@forEachIndexed
        val d = (if (c.closedToday) ChamberClock.firstBookableDay(c, t0 + DAY_MS - Rules.DHAKA_OFFSET_MS) else ChamberClock.firstBookableDay(c, now)) ?: return@forEachIndexed
        if (best == null || d < best!!.first) best = d to i
    }
    val (d, i) = best ?: return null
    if (d - t0 > 14 * DAY_MS) return null
    val label = when ((d - t0) / DAY_MS) {
        0L -> "আজ"
        1L -> "কাল"
        else -> Rules.formatDate(d).substringBeforeLast(' ')
    }
    return NextSlot(d.toString(), label, true, i)
}

fun providerKeyLine(p: Provider, chamber: Chamber?): String = Listing.keyLine(
    p.extras.specialtyLabel.ifBlank { p.specialties.take(2).joinToString(", ") { Specialties.label(it) } }.ifBlank { p.degrees },
    Practice.nameTag(p.type, p.system),
    Listing.feeAmount(p.extras.feeAmount) ?: chamber?.let { Listing.fee(it.fee, it.noFee) },
)

fun providerBadges(p: Provider): List<ListingBadge> = listOfNotNull(
    Listing.slotBadge(providerNextSlot(p))?.let { ListingBadge(it, if (it == ListingText.slotToday()) BadgeTone.GOOD else BadgeTone.NEUTRAL) },
    if (OnlineGate.on(OnlineGate.CHAMBER) && p.chambers.any { it.online }) ListingBadge(tr("Online", "অনলাইন"), BadgeTone.INFO) else null,
    if (p.homeVisit?.enabled == true) ListingBadge(tr("Home visit", "বাসায় যান"), BadgeTone.INFO) else null,
)

/** onBook: "সিরিয়াল নিন" (অ্যাপে অ্যাপয়েন্টমেন্ট নেন তবেই); ফোন = কার্ডের চেম্বারের সিরিয়ালের ফোন */
fun providerCard(p: Provider, filter: CareFilter, onOpen: () -> Unit, onBook: (() -> Unit)?, onReport: (() -> Unit)? = null): ListingCardData {
    val c = providerChamber(p, filter)
    return ListingCardData(
        id = p.uid, type = ListingType.DOCTOR, name = p.name, icon = Badges.icon(p.type), photo = p.photo,
        keyLine = providerKeyLine(p, c), onOpen = onOpen, verified = p.extras.verified,
        note = c?.label?.let { l -> if (p.chambers.size > 1) l + tr(" +${p.chambers.size - 1}", " +" + num(p.chambers.size - 1)) else l },
        ratingText = ratingText(ratingOf(p.extras, p.rating, p.ratingCount)),
        badges = providerBadges(p),
        primary = if (p.canBook && onBook != null) CardAction(ListingText.book(), R.drawable.ic_calendar_month, onBook) else null,
        contact = c?.serialPhone?.takeIf { it.isNotBlank() }?.let { ListingContact(it) },
        onReport = onReport,
    )
}
