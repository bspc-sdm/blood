<script setup lang="ts">
// রোগী: পেমেন্টের সমস্যা জানান (pay_report {id, reason, note?}) — অ্যাডমিন দেখেন; Lifolo নিজে টাকা ফেরত দিতে পারে না
import { ref } from 'vue';
import { Button, ChoiceChips, Dialog, ErrorText, TextArea } from '@/ui/kit';
import { errorText } from '@/lib/api';
import { tr } from '@/lib/i18n';
import * as api from './api';
import { REPORT_REASONS, T, reportText } from './text';
import type { PayRequest } from './model';

const props = defineProps<{ req: PayRequest; reason?: string | null }>();
const emit = defineEmits<{ close: []; done: [] }>();
const reason = ref<string>(
  props.reason ?? (props.req.status === 'refund_claimed' || props.req.status === 'refunded' || props.req.status === 'refund_due' ? 'no_refund' : props.req.status === 'claimed' || props.req.status === 'rejected' ? 'not_confirmed' : 'asked_other_number'),
);
const note = ref('');
const busy = ref(false);
const err = ref<string | null>(null);
async function send() {
  busy.value = true;
  err.value = null;
  try {
    await api.report(props.req.id, reason.value, note.value);
    emit('done');
  } catch (e) {
    err.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <Dialog size="small" :title="T.reportTitle()" @close="() => !busy && emit('close')">
    <span class="t-label-l">{{ T.reportWhat() }}</span>
    <ChoiceChips v-model="reason" :options="REPORT_REASONS.map((r) => ({ value: r, label: reportText(r) }))" data-testid="report-reasons" />
    <TextArea :label="T.reportNote()" :rows="3" data-testid="report-note" :model-value="note" @update:model-value="(v: string) => (note = v.slice(0, 500))" />
    <p class="t-body-s muted">{{ T.reportHint() }}</p>
    <ErrorText :text="err" />
    <template #actions>
      <Button variant="text" :disabled="busy" @click="emit('close')">{{ tr('Cancel', 'বাতিল') }}</Button>
      <Button :busy="busy" data-testid="report-send" @click="send">{{ T.reportSend() }}</Button>
    </template>
  </Dialog>
</template>
