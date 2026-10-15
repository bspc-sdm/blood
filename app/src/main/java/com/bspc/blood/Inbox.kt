package com.bspc.blood

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

data class InboxItem(
    val id: String,
    val title: String,
    val body: String,
    /** নোটিফিকেশনের data (type, requestId, questionId, orderId…) */
    val data: Map<String, String>,
    val read: Boolean,
    val createdAt: Long,
)

object InboxApi {
    private suspend fun call(action: String): JSONObject = ServerApi.post("/api/community", JSONObject().put("action", action))

    suspend fun list(): List<InboxItem> {
        val arr = call("inbox").optJSONArray("items") ?: JSONArray()
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val d = o.optJSONObject("data") ?: JSONObject()
            InboxItem(
                o.optString("id"), o.optString("title"), o.optString("body"),
                d.keys().asSequence().associateWith { d.optString(it) },
                o.optBoolean("read"), o.optLong("createdAt"),
            )
        }
    }

    suspend fun markAllRead() {
        call("inbox_read")
    }
}

/** নোটিফিকেশনের ধরন অনুযায়ী আইকন */
private fun inboxIcon(type: String?): Int = when (type) {
    "appointment" -> R.drawable.ic_calendar_month
    "homevisit", "homecare" -> R.drawable.ic_home_health
    "answer" -> R.drawable.ic_forum_outline
    "pharmacy_order", "pharmacy_mine", "med_find", "med_find_mine" -> R.drawable.ic_medication
    "services" -> R.drawable.ic_storefront
    "support" -> R.drawable.ic_help
    "emergency" -> R.drawable.ic_warning
    "eligible" -> R.drawable.ic_favorite
    else -> R.drawable.ic_water_drop
}

@Composable
fun InboxScreen(onBack: () -> Unit, onOpen: (Map<String, String>) -> Unit) {
    var loading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var list by remember { mutableStateOf<List<InboxItem>>(emptyList()) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(reload) {
        loading = true
        runCatching { InboxApi.list() }
            .onSuccess {
                list = it
                errorMsg = null
                // খুললেই সব পড়া ধরা হয় (কোনগুলো নতুন ছিল তা এবার হালকা রঙে দেখায়)
                if (it.any { x -> !x.read }) runCatching { InboxApi.markAllRead() }
            }
            .onFailure { errorMsg = it.message }
        loading = false
    }

    Scaffold(
        topBar = {
            BackTopBar(tr("Notifications", "নোটিফিকেশন"), onBack) {
                IconButton(onClick = { reload++ }) { AppIcon(R.drawable.ic_refresh, tr("Refresh", "রিফ্রেশ")) }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(vertical = 8.dp)) {
            item { Box(Modifier.padding(horizontal = 16.dp)) { ErrorText(errorMsg) } }
            when {
                loading && list.isEmpty() -> item { LoadingBox() }
                list.isEmpty() -> item {
                    Box(Modifier.fillParentMaxHeight(0.7f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        EmptyState(R.drawable.ic_notifications, tr("No notifications yet", "এখনো কোনো নোটিফিকেশন নেই"), null)
                    }
                }
                else -> items(list, key = { it.id }) { n ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onOpen(n.data) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = if (n.read) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(40.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                AppIcon(inboxIcon(n.data["type"]), null, Modifier.size(22.dp))
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                n.title,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = if (n.read) FontWeight.Normal else FontWeight.Bold,
                            )
                            if (n.body.isNotBlank()) {
                                Text(n.body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(timeAgo(n.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (!n.read) {
                            Surface(color = MaterialTheme.colorScheme.primary, shape = CircleShape, modifier = Modifier.padding(top = 6.dp).size(8.dp)) {}
                        }
                    }
                    HorizontalDivider(Modifier.padding(start = 68.dp))
                }
            }
        }
    }
}

/** উপরের বারের ঘণ্টা: না-পড়া থাকলে সংখ্যা */
@Composable
fun BellButton(unread: Int, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        BadgedBox(badge = {
            if (unread > 0) Badge { Text(if (unread > 99) "99+" else num(unread)) }
        }) {
            // গোল ধাঁচের ঘণ্টা: না-পড়া থাকলে ভরাট, নইলে শুধু রেখা
            AppIcon(if (unread > 0) R.drawable.ic_notifications_round_fill else R.drawable.ic_notifications_round, tr("Notifications", "নোটিফিকেশন"))
        }
    }
}
