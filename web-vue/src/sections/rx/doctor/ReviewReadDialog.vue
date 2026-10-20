<script setup lang="ts">
// রিপোর্ট পড়ে জানানো
import { ref } from 'vue';
import { Button, Dialog, ErrorText, Loading, TextArea } from '@/ui/kit';
import { call, errorText } from '@/lib/api';
import { useCall } from '@/lib/query';
import { tr } from '@/lib/i18n';
import { SELF, type LabHistory, type LabOrder, type RxReview } from './model';
import LabReportView from './LabReportView.vue';

const props = defineProps<{ r: RxReview }>();
const emit = defineEmits<{ close: []; done: [] }>();
const q = useCall<{ item: LabOrder; history?: LabHistory }>('lab_order_get', { id: props.r.labOrderId, ...SELF });
const comment = ref('');
const busy = ref(false);
const err = ref<string | null>(null);
async function send() {
  if (!comment.value.trim()) return;
  busy.value = true;
  err.value = null;
  try {
    await call('review_done', { id: props.r.id, comment: comment.value.trim() });
    emit('done');
  } catch (e) {
    err.value = errorText(e);
  }
  busy.value = false;
}
function onKey(e: KeyboardEvent) {
  if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
    e.preventDefault();
    send();
  }
}
</script>

<template>
  <Dialog size="wide" :title="r.patientName || tr('Test report', 'পরীক্ষার রিপোর্ট')" @close="() => !busy && emit('close')">
    <Loading v-if="q.isPending" />
    <ErrorText v-else-if="q.isError" :text="errorText(q.error)" />
    <LabReportView v-else-if="q.data" :o="q.data.item" :history="q.data.history ?? {}" />
    <TextArea :label="tr('What should the patient know?', 'রোগীকে কী জানাবেন?')" :rows="3" :model-value="comment" @update:model-value="(v) => (comment = v.slice(0, 1000))" @keydown="onKey" />
    <p class="t-body-s muted">{{ tr('Need to change medicines? Write a new prescription from the Prescriptions tab.', 'ওষুধ বদলাতে হলে "প্রেসক্রিপশন" থেকে নতুন লিখুন।') }}</p>
    <ErrorText :text="err" />
    <template #actions>
      <Button variant="text" :disabled="busy" @click="emit('close')">{{ tr('Cancel', 'বাতিল') }}</Button>
      <Button icon="send" :busy="busy" :disabled="!comment.trim()" @click="send">{{ tr('Send to patient', 'রোগীকে পাঠান') }}</Button>
    </template>
  </Dialog>
</template>
