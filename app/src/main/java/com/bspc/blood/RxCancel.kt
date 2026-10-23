package com.bspc.blood

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * প্রেসক্রিপশন বাতিলের কারণ (rx_cancel {id, reason, note?} — backend/docs/RX-STATE.md "Cancellation reason"):
 * ডাক্তার বাছেন ([RxCancelDialog]), রোগী আর কেয়ারগিভার দেখেন (state.cancelReason/cancelNote, টাইমলাইন — RxStatus.kt)।
 * যাচাইয়ের পাতা, ফার্মেসি বা পুশে কারণ কখনো যায় না। ওয়েবের rxcancel.ts / RxCancelDialog.vue-র মতো।
 */
object RxCancel {
    val REASONS = listOf("wrong_medicine", "wrong_patient", "replaced", "patient_reported", "other")
    const val NOTE_MIN = 3
    const val NOTE_MAX = 200

    /** চাবি → লেখা; অজানা বা নেই (আগের release-এ বাতিল) → null */
    fun reasonText(key: String?): String? = when (key) {
        "wrong_medicine" -> tr("Wrong medicine/dose", "ভুল ওষুধ/মাত্রা")
        "wrong_patient" -> tr("Wrong patient", "ভুল রোগী")
        "replaced" -> tr("Gave a new prescription", "নতুন প্রেসক্রিপশন দিয়েছি")
        "patient_reported" -> tr("Patient reported a problem", "রোগী জানিয়েছেন সমস্যা")
        "other" -> tr("Other reason", "অন্য কারণ")
        else -> null
    }

    /** "বাতিল করেছেন ডাক্তার — <কারণ>" (কারণ না থাকলে শুধু প্রথম অংশ) */
    fun cancelledLine(key: String?): String {
        val base = tr("Cancelled by the doctor", "বাতিল করেছেন ডাক্তার")
        return reasonText(key)?.let { "$base — $it" } ?: base
    }

    /** "অন্য কারণ"-এ নোট ৩–২০০ অক্ষর, বাকিগুলোয় ঐচ্ছিক (সর্বোচ্চ ২০০) */
    fun noteOk(reason: String?, note: String): Boolean {
        val n = note.trim().length
        return reason != null && n <= NOTE_MAX && (reason != "other" || n >= NOTE_MIN)
    }
}

/**
 * ডাক্তারের বাতিল: ১) কারণ (রেডিও) + নোট → ২) নিশ্চিত → rx_cancel। শুধু লেখা ডাক্তার দেখেন (সার্ভারও আটকায়)।
 * [send] পরীক্ষায় বদলানো যায়।
 */
@Composable
fun RxCancelDialog(
    rxId: String,
    onDismiss: () -> Unit,
    onDone: () -> Unit,
    send: suspend (id: String, reason: String, note: String?) -> Unit = { id, r, n -> RxApi.cancel(id, r, n) },
) {
    val scope = rememberCoroutineScope()
    var reason by rememberSaveable { mutableStateOf<String?>(null) }
    var note by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val ok = RxCancel.noteOk(reason, note)
    val needNote = reason == "other"
    val len = note.trim().length
    // FormDialog: সাধারণ AlertDialog-এ লেখার ঘর আর রেডিও একসাথে থাকলে টেস্টে পর্দা স্থির হয় না (AreaPicker.kt)
    FormDialog(
        title = tr("Cancel this prescription?", "প্রেসক্রিপশনটা বাতিল করবেন?"),
        onDismiss = { if (!busy) onDismiss() },
        content = {
            Column(Modifier.testTag("rx-cancel-dialog"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!confirm) {
                    Text(tr("Pharmacies will see it as cancelled. You can write a new one.", "ফার্মেসি যাচাই করলে বাতিল দেখাবে। চাইলে নতুন করে লিখতে পারেন।"), style = MaterialTheme.typography.bodyMedium)
                    Text(tr("Why are you cancelling?", "কেন বাতিল করছেন?"), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    Column(Modifier.selectableGroup()) {
                        RxCancel.REASONS.forEach { r ->
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                    .selectable(selected = reason == r, role = Role.RadioButton, onClick = { reason = r })
                                    .testTag("rx-cancel-$r"),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = reason == r, onClick = null)
                                Text(RxCancel.reasonText(r).orEmpty(), Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                    OutlinedTextField(
                        value = note,
                        onValueChange = { if (it.length <= RxCancel.NOTE_MAX) note = it },
                        label = {
                            Text(if (needNote) tr("Write the reason (3–200 characters)", "কারণটা লিখুন (৩–২০০ অক্ষর)") else tr("Note (optional)", "নোট (ঐচ্ছিক)"))
                        },
                        supportingText = {
                            Text(
                                if (needNote && note.isNotEmpty() && len < RxCancel.NOTE_MIN) tr("At least 3 characters", "অন্তত ৩ অক্ষর")
                                else num(len) + "/" + num(RxCancel.NOTE_MAX),
                            )
                        },
                        isError = needNote && note.isNotEmpty() && len < RxCancel.NOTE_MIN,
                        minLines = 2,
                        maxLines = 5,
                        modifier = Modifier.fillMaxWidth().testTag("rx-cancel-note"),
                    )
                    Text(
                        tr("The patient will see the reason and the note. Pharmacies and the QR check only see “cancelled”.", "রোগী কারণ আর নোট দেখবেন। ফার্মেসি আর QR যাচাইয়ে শুধু “বাতিল” দেখাবে।"),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Column(
                        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(10.dp)).padding(12.dp)
                            .testTag("rx-cancel-summary"),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(tr("Reason", "কারণ"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(RxCancel.reasonText(reason).orEmpty(), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        if (note.isNotBlank()) Text(note.trim(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(tr("It can't be undone. The patient will be told it was cancelled and why.", "এটা আর ফেরানো যাবে না। রোগী দেখবেন যে প্রেসক্রিপশনটা বাতিল হয়েছে আর কেন।"), style = MaterialTheme.typography.bodyMedium)
                }
                ErrorText(error)
            }
        },
        confirmButton = {
            if (!confirm) {
                TextButton(onClick = { error = null; confirm = true }, enabled = ok, modifier = Modifier.testTag("rx-cancel-next")) { Text(tr("Next", "পরের ধাপ")) }
            } else {
                TextButton(
                    onClick = {
                        val r = reason ?: return@TextButton
                        scope.launch {
                            busy = true
                            error = null
                            runCatching { send(rxId, r, note.trim().ifEmpty { null }) }
                                .onSuccess { onDone() }
                                .onFailure { error = it.message }
                            busy = false
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.testTag("rx-cancel-confirm"),
                ) { Text(tr("Cancel it", "বাতিল করুন"), color = MaterialTheme.colorScheme.error) }
            }
        },
        dismissButton = {
            if (!confirm) {
                TextButton(onClick = onDismiss) { Text(tr("Keep", "রেখে দিন")) }
            } else {
                TextButton(onClick = { confirm = false }, enabled = !busy) { Text(tr("Back", "ফিরে যান")) }
            }
        },
    )
}
