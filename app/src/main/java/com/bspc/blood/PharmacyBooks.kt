package com.bspc.blood

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.util.Locale

// ফার্মেসির হিসাব-খাতা (পারভেজ, ১৮ অক্টোবর ২০২৬): পণ্য-দাম, মাল কেনা, বিক্রি, লাভ-ক্ষতি — পুরোটা শুধু ওয়েবে
// (ফোনের ব্রাউজারেও চলে), অ্যাপ হালকা থাকে। অ্যাপে শুধু এই ছোট "আজকের হিসাব" কার্ড আর "পুরো হিসাব" বাটন (ওয়েব পাতা খোলে)।
// সার্ভার: books_today (server/lib/shopbooks.js)। PharmacyRxSection-এ এক লাইন: PharmacyTodayCard()

data class BooksToday(val sales: Int, val net: Double, val profit: Double, val low: Int, val expiring: Int, val priced: Boolean)

object PharmacyBooks {
    /** ওয়েবের হিসাবের পাতা (web/src/sections/rx/partner/pharmacy.tsx-এর /books ট্যাব) */
    const val WEB_PATH = "/work/pharmacy/orders/books"

    suspend fun today(): BooksToday {
        val o = ServerApi.post("/api/community", JSONObject().put("action", "books_today"))
        return BooksToday(
            o.optInt("sales"), o.optDouble("net", 0.0), o.optDouble("profit", 0.0),
            o.optInt("low"), o.optInt("expiring"), o.optBoolean("priced"),
        )
    }

    /** ৳১,৯৫৯ / ৳৩৯৫.১৫ (বাংলায় বাংলা অঙ্ক) */
    fun taka(v: Double): String {
        val a = Math.abs(v)
        val s = if (a == Math.floor(a)) String.format(Locale.US, "%,d", a.toLong())
        else String.format(Locale.US, "%,.2f", a).trimEnd('0').trimEnd('.')
        return (if (v < 0) "−" else "") + "৳" + num(s)
    }
}

/** দোকানের মালিকের কাজের পাতায়: আজকের বিক্রি, লাভ, কম স্টক, মেয়াদ শেষের পথে; "পুরো হিসাব" ওয়েবে */
@Composable
fun PharmacyTodayCard() {
    val context = LocalContext.current
    var t by remember { mutableStateOf<BooksToday?>(null) }
    LaunchedEffect(Unit) { runCatching { PharmacyBooks.today() }.onSuccess { t = it } }
    val d = t ?: return
    val cs = MaterialTheme.colorScheme
    Card(colors = CardDefaults.cardColors(containerColor = cs.surface)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(tr("Today's accounts", "আজকের হিসাব"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (!d.priced && d.sales == 0) {
                SmallNote(tr("Open on the web to add prices and stock", "দাম আর স্টক বসাতে ওয়েবে খুলুন"))
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("Sales", "বিক্রি"), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                        Text(PharmacyBooks.taka(d.net), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(tr("${d.sales} sales", "${num(d.sales)}টা বিক্রি"), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(tr("Profit", "লাভ"), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                        Text(
                            PharmacyBooks.taka(d.profit), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                            color = if (d.profit < 0) cs.error else cs.primary,
                        )
                    }
                }
                if (d.low > 0 || d.expiring > 0) {
                    SmallNote(
                        listOfNotNull(
                            if (d.low > 0) tr("${d.low} low on stock", "কম স্টক ${num(d.low)}টা") else null,
                            if (d.expiring > 0) tr("${d.expiring} expiring soon", "মেয়াদ শেষের পথে ${num(d.expiring)}টা") else null,
                        ).joinToString(" · "),
                    )
                }
            }
            OutlinedButton(onClick = { openUrl(context, ServerApi.BASE_URL + PharmacyBooks.WEB_PATH) }, modifier = Modifier.fillMaxWidth()) {
                ButtonContent(false, tr("Full accounts", "পুরো হিসাব"), R.drawable.ic_payments)
            }
        }
    }
}
