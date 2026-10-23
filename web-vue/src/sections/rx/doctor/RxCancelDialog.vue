<script setup lang="ts">
// প্রেসক্রিপশন বাতিল: ১) কারণ (রেডিও) + নোট ("অন্য কারণ"-এ লাগবে, ৩–২০০ অক্ষর) → ২) নিশ্চিত করা → rx_cancel {id, reason, note}।
// শুধু লেখা ডাক্তার (সহকারী নন — সার্ভারও আটকায়)। রোগী কারণটা দেখেন; যাচাইয়ের পাতা আর ফার্মেসি শুধু "বাতিল"। অ্যাপের RxCancelDialog।
import { computed, ref } from 'vue';
import { Button, Dialog, ErrorText, TextArea } from '@/ui/kit';
import { call, errorText } from '@/lib/api';
import { num, tr } from '@/lib/i18n';
import { CANCEL_REASONS, NOTE_MAX, NOTE_MIN, cancelReasonText, noteOk, type CancelReason } from '../../health/rxcancel';

const props = defineProps<{ rxId: string }>();
const emit = defineEmits<{ close: []; done: [] }>();
const reason = ref<CancelReason | null>(null);
const note = ref('');
const step = ref<'pick' | 'confirm'>('pick');
const busy = ref(false);
const err = ref<string | null>(null);
const ok = computed(() => noteOk(reason.value, note.value));
const needNote = computed(() => reason.value === 'other');
const len = computed(() => note.value.trim().length);
const noteLabel = computed(() =>
  needNote.value
    ? tr(`Write the reason (${NOTE_MIN}–${NOTE_MAX} characters)`, 'কারণটা লিখুন (৩–২০০ অক্ষর)') // bn-ok
    : tr('Note (optional)', 'নোট (ঐচ্ছিক)'), // bn-ok
);
const noteErr = computed(() =>
  needNote.value && note.value.length > 0 && len.value < NOTE_MIN
    ? tr(`At least ${NOTE_MIN} characters`, 'অন্তত ৩ অক্ষর') // bn-ok
    : null,
);
const noteHint = computed(() => tr(`${len.value}/${NOTE_MAX}`, `${num(len.value)}/${num(NOTE_MAX)}`));

function next() {
  if (!ok.value) return;
  err.value = null;
  step.value = 'confirm';
}
async function confirm() {
  if (!ok.value || !reason.value) return;
  busy.value = true;
  err.value = null;
  try {
    const n = note.value.trim();
    await call('rx_cancel', { id: props.rxId, reason: reason.value, ...(n ? { note: n } : {}) });
    emit('done');
  } catch (e) {
    err.value = errorText(e);
  }
  busy.value = false;
}
const close = () => !busy.value && emit('close');
</script>

<template>
  <!-- ConfirmDialog-এর মতো, তবে অ্যাপের "রেখে দিন" — পাশাপাশি "বাতিল" আর "বাতিল করুন" গুলিয়ে যায় -->
  <Dialog size="small" :title="tr('Cancel this prescription?', 'প্রেসক্রিপশনটা বাতিল করবেন?')" @close="close">
    <template v-if="step === 'pick'">
      <div class="t-body-m muted">{{ tr('Pharmacies will see it as cancelled. You can write a new one.', 'ফার্মেসি যাচাই করলে বাতিল দেখাবে। চাইলে নতুন করে লিখতে পারেন।') }}</div>
      <fieldset class="rxc-reasons" data-testid="rx-cancel-reasons">
        <legend class="t-label-l">{{ tr('Why are you cancelling?', 'কেন বাতিল করছেন?') }}</legend> <!-- bn-ok -->
        <label v-for="r in CANCEL_REASONS" :key="r" :class="['rxc-reason', reason === r && 'on']">
          <input v-model="reason" type="radio" name="rx-cancel-reason" :value="r" :data-reason="r" />
          <span>{{ cancelReasonText(r) }}</span>
        </label>
      </fieldset>
      <TextArea
        v-model="note"
        :label="noteLabel"
        :hint="noteHint"
        :error="noteErr"
        :maxlength="NOTE_MAX"
        rows="3"
        data-testid="rx-cancel-note"
      />
      <span class="t-body-s muted">{{ tr('The patient will see the reason and the note. Pharmacies and the QR check only see “cancelled”.', 'রোগী কারণ আর নোট দেখবেন। ফার্মেসি আর QR যাচাইয়ে শুধু “বাতিল” দেখাবে।') }}</span> <!-- bn-ok -->
    </template>
    <template v-else>
      <div class="rxc-summary" data-testid="rx-cancel-summary">
        <span class="t-label-m muted">{{ tr('Reason', 'কারণ') }}</span> <!-- bn-ok -->
        <span class="t-title-s">{{ cancelReasonText(reason) }}</span>
        <span v-if="note.trim()" class="rxc-note">{{ note.trim() }}</span>
      </div>
      <div class="t-body-m">{{ tr("It can't be undone. The patient will be told it was cancelled and why.", 'এটা আর ফেরানো যাবে না। রোগী দেখবেন যে প্রেসক্রিপশনটা বাতিল হয়েছে আর কেন।') }}</div> <!-- bn-ok -->
    </template>
    <ErrorText :text="err" />
    <template #actions>
      <template v-if="step === 'pick'">
        <Button variant="text" @click="close">{{ tr('Keep', 'রেখে দিন') }}</Button>
        <Button variant="tonal" :disabled="!ok" data-testid="rx-cancel-next" @click="next">{{ tr('Next', 'পরের ধাপ') }}</Button> <!-- bn-ok -->
      </template>
      <template v-else>
        <Button variant="text" :disabled="busy" @click="step = 'pick'">{{ tr('Back', 'ফিরে যান') }}</Button>
        <Button variant="danger" :busy="busy" data-testid="rx-cancel-confirm" @click="confirm">{{ tr('Cancel it', 'বাতিল করুন') }}</Button>
      </template>
    </template>
  </Dialog>
</template>

<style scoped>
.rxc-reasons { border: 0; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.rxc-reasons legend { padding: 0; margin-bottom: 6px; color: var(--on-surface); }
.rxc-reason {
  display: flex; align-items: center; gap: 10px; min-height: 44px; padding: 6px 10px; border-radius: 10px; cursor: pointer;
  font-size: 15px; line-height: 20px; color: var(--on-surface);
}
.rxc-reason:hover { background: var(--surface-high); }
.rxc-reason.on { background: var(--secondary-container); color: var(--on-secondary-container); }
.rxc-reason input { width: 18px; height: 18px; margin: 0; accent-color: var(--primary); flex: none; }
.rxc-reason span { min-width: 0; overflow-wrap: anywhere; }
.rxc-summary { display: flex; flex-direction: column; gap: 4px; padding: 10px 12px; border-radius: 10px; background: var(--surface-high); }
.rxc-note { font-size: 14px; line-height: 20px; color: var(--on-surface-variant); overflow-wrap: anywhere; white-space: pre-line; }
</style>
