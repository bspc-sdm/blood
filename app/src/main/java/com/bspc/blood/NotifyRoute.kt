package com.bspc.blood

/**
 * নোটিফিকেশন চাপলে (পুশ, বা ঘণ্টার তালিকা থেকে) কোথায় যাবে — শুধু হিসাব, কিছু খোলে না (App-এর openFromData খোলে)।
 * সার্ভারের ২১ ধরনের পুশ (backend: PushService ও তার ডাকগুলো) আর ওয়েবের একই গন্তব্যের সাথে মিলিয়ে
 * (e2e/web/journeys/11-notifications.mjs-এর androidTarget/webTarget)।
 */
sealed interface NotifyRoute {
    /** মূল পাতার একটা ট্যাব (উপরের সব পাতা বন্ধ); servicesTarget: সেবা ট্যাবের ভেতরে কোন অংশ; homeCare: "বাসায় সেবা" অংশ */
    data class Tab(val tab: Int, val servicesTarget: String? = null, val homeCare: Boolean = false) : NotifyRoute

    /** মূল পাতার উপরে একটা পাতা; workTarget: নিজের ব্যবসার পাতায় কোন ভাগ ("orders" | "finds") */
    data class Open(val screen: Screen, val workTarget: String? = null) : NotifyRoute

    /** নিজের একটা প্রেসক্রিপশন */
    data class Rx(val id: String) : NotifyRoute

    /** কেয়ারগিভার: রোগীর হয়ে (কেয়ারগিভার মোডে) তাঁর প্রেসক্রিপশন */
    data class CareRx(val asUid: String, val rxId: String) : NotifyRoute

    /** নতুন রসিদ (invoice_new) — asUid: কেয়ারগিভারকে রোগীর রসিদ */
    data class Invoice(val id: String, val asUid: String? = null, val manage: Boolean = false) : NotifyRoute

    /** রসিদের QR / lifolo.com/v/<কোড> — যাচাইয়ের ছোট পাতা (লগইন লাগে না) */
    data class VerifyInvoice(val code: String) : NotifyRoute
    /** অনলাইন অ্যাপয়েন্টমেন্টের ভিডিও কলে যুক্ত হওয়ার পর্দা (route "appt_call/{appointmentId}", CallsNav.kt) */
    data class ApptCall(val appointmentId: String) : NotifyRoute
}

/**
 * @param approvedBadge নিজের অনুমোদিত ব্যাজ (role ছাড়া পুরোনো "appointment" খবরে কোন পাতা — ডাক্তার হলে চেম্বার)
 * @param hasWork "আমার কাজ" ট্যাব আছে কি না (kind ছাড়া "services" খবরে)
 */
fun notifyRoute(data: Map<String, String>, approvedBadge: String? = null, hasWork: Boolean = false): NotifyRoute {
    val type = data["type"]
    val requestId = data["requestId"]?.takeIf { it.isNotBlank() }
    val questionId = data["questionId"]?.takeIf { it.isNotBlank() }
    return when {
        // রক্তের রিকোয়েস্ট: রাজি ডোনারের খবর রক্তপ্রার্থীর নিজের রিকোয়েস্টে, বাকি সব (ডোনারকে) রিকোয়েস্টের বিস্তারিত
        requestId != null -> NotifyRoute.Open(if (type == "response") Screen.MyRequestDetail(requestId) else Screen.RequestDetail(requestId))
        questionId != null -> NotifyRoute.Open(Screen.QuestionDetail(questionId))
        // চেম্বারে আসা বুকিং (role=provider) → "আমার চেম্বার"; নিজের নেওয়া অ্যাপয়েন্টমেন্ট (role=patient) → "আমার অ্যাপয়েন্টমেন্ট"।
        // role ছাড়া (পুরোনো খবর): ডাক্তার/পুষ্টিবিদ/ট্রেনার হলে চেম্বার
        type == "appointment" -> NotifyRoute.Open(
            when (data["role"]) {
                "provider" -> Screen.ChamberEdit
                "patient" -> Screen.MyAppointments
                else -> if (approvedBadge in CHAMBER_TYPES) Screen.ChamberEdit else Screen.MyAppointments
            },
        )
        // বাসায় সেবা (আগের হোম নার্সের "homevisit" সহ): অ্যাপয়েন্টমেন্ট ট্যাবের "বাসায় সেবা" অংশ
        type == "homevisit" || type == "homecare" -> NotifyRoute.Tab(Tabs.APPOINTMENT, homeCare = true)
        type == "support" -> NotifyRoute.Open(Screen.Help())
        // অ্যাপে কল: মিসড কল → কলের তালিকা; "ডাক্তার ভিডিও কলে আছেন" → যুক্ত হওয়ার পর্দা (id না থাকলে আমার অ্যাপয়েন্টমেন্ট)
        type == "call_missed" || type == "call_incoming" -> NotifyRoute.Open(Screen.CallHistory)
        // মেসেজ: সেই কথোপকথন (id না থাকলে মেসেজের তালিকা)
        type == "chat_message" -> data["threadId"]?.takeIf { it.isNotBlank() }?.let { NotifyRoute.Open(Screen.Chat(ChatNav.Target(threadId = it))) }
            ?: NotifyRoute.Open(Screen.ChatInbox)
        type == "appt_call_ready" -> data["appointmentId"]?.takeIf { it.isNotBlank() }?.let { NotifyRoute.ApptCall(it) }
            ?: NotifyRoute.Open(Screen.MyAppointments)
        // রোগীর অ্যাম্বুলেন্স ডাকা: কেউ রাজি হলেন/পৌঁছালেন/বাতিল/সময় শেষ — সেই অপেক্ষার পাতা
        type == "amb_req_mine" -> data["reqId"]?.takeIf { it.isNotBlank() }?.let { NotifyRoute.Open(Screen.AmbulanceRequest(it)) }
            ?: NotifyRoute.Tab(Tabs.SERVICES)
        // প্রেসক্রিপশন (নতুন, দোকান/প্যাথলজির খবর, রিপোর্ট, ওষুধ শেষ হওয়ার আগে): সরাসরি সেই প্রেসক্রিপশন।
        // rxId না থাকলে (ওষুধের অ্যালার্ম) তালিকা; পুরোনো খবরে শুধু id (সেটাও প্রেসক্রিপশনের id)
        type == "rx" -> (data["rxId"] ?: data["id"])?.takeIf { it.isNotBlank() }?.let { NotifyRoute.Rx(it) }
            ?: NotifyRoute.Open(Screen.MyPrescriptions)
        type == "rx_review" -> NotifyRoute.Open(Screen.RxReviews)
        // রসিদ: সরাসরি সেই রসিদ; id না থাকলে "আমার রসিদ"
        type == "invoice_new" -> data["invoiceId"]?.takeIf { it.isNotBlank() }?.let { NotifyRoute.Invoice(it, data["asUid"]?.takeIf { u -> u.isNotBlank() }) }
            ?: NotifyRoute.Open(Screen.MyReceipts())
        // ডাক্তারকে: সহকারী একটা রসিদ বাতিল করেছেন — সেই রসিদ (ব্যবসার দিক থেকে)
        type == "invoice_void" -> data["invoiceId"]?.takeIf { it.isNotBlank() }?.let { NotifyRoute.Invoice(it, manage = true) } ?: NotifyRoute.Open(Screen.ChamberEdit, "fees")
        // মালিকের "দিনের শেষ" (বাতিল, বড় ছাড়, নষ্ট দেখানো স্টক): kind থাকলে সেই ব্যবসার রসিদ (ডাক্তার — "ফি ও রসিদ"), না থাকলে "আমার কাজ"
        type == "invoice_summary" || type == "invoice_day_end" -> when (val k = data["kind"]) {
            "chamber" -> NotifyRoute.Open(Screen.ChamberEdit, "fees")
            in BUSINESS_KINDS -> NotifyRoute.Open(Screen.MyBusiness(k!!), "receipts")
            else -> NotifyRoute.Tab(if (hasWork) Tabs.WORK else Tabs.HOME)
        }
        type == "invoice_verify" -> data["code"]?.takeIf { it.isNotBlank() }?.let { NotifyRoute.VerifyInvoice(it) } ?: NotifyRoute.Tab(Tabs.HOME)
        // কেয়ারগিভারকে রোগীর প্রেসক্রিপশনের খবর: রোগীর হয়ে সেই প্রেসক্রিপশন; অন্য খবর / নতুন আমন্ত্রণ: "কেয়ারগিভার ও পরিবার"
        type == "care" && !data["asUid"].isNullOrBlank() && !data["rxId"].isNullOrBlank() -> NotifyRoute.CareRx(data["asUid"]!!, data["rxId"]!!)
        type == "care" -> NotifyRoute.Open(Screen.Care)
        // ফার্মেসি/প্যাথলজির মালিক: নতুন অর্ডার/অনুরোধ → "নতুন" ভাগ; ওষুধ খোঁজার অনুরোধ → "দোকান" ট্যাব
        type == "rx_pharmacy" -> NotifyRoute.Open(Screen.MyBusiness("pharmacy"), "orders")
        type == "med_find" -> NotifyRoute.Open(Screen.MyBusiness("pharmacy"), "finds")
        type == "rx_lab" -> NotifyRoute.Open(Screen.MyBusiness("lab"), "orders")
        type == "pharmacy_order" || type == "pharmacy_mine" -> NotifyRoute.Open(Screen.MyBusiness("pharmacy"), "orders")
        // নিজের ওষুধ খোঁজার উত্তর: সেবা › ফার্মেসি › "ওষুধ খুঁজুন"; Google Maps থেকে জায়গা: সেবা › অ্যাম্বুলেন্স ডাকুন
        type == "med_find_mine" || type == "amb_call" -> NotifyRoute.Tab(Tabs.SERVICES, servicesTarget = type)
        // ব্যবসার অনুমোদন/বাতিল/কাগজ চাওয়া, অ্যাম্বুলেন্স চালককে ডাক (kind: কোন ব্যবসা) — নিজের সেই পাতা
        type == "services" -> data["kind"]?.takeIf { it in BUSINESS_KINDS }?.let { NotifyRoute.Open(Screen.MyBusiness(it)) }
            ?: NotifyRoute.Tab(if (hasWork) Tabs.WORK else Tabs.HOME)
        // পেশাদার ব্যাজ (অনুমোদন/বাতিল/সনদের ছবি চাওয়া) আর শেষ রক্তদানের তারিখ সংশোধনের ফল: প্রোফাইল — ব্যাজের সারি,
        // কাগজ দেওয়ার জায়গা আর তারিখ সেখানেই (ওয়েব: /health/badge)
        type == "badge" || type == "correction" -> NotifyRoute.Tab(Tabs.PROFILE)
        // আবার রক্ত দিতে পারবেন, রিকোয়েস্ট তুলে নেওয়া হয়েছে, চাওয়া হাসপাতাল যোগ হয়েছে, জরুরি আহ্বান (উপরের ব্যানার), বাকি সব: হোম
        else -> NotifyRoute.Tab(Tabs.HOME)
    }
}
