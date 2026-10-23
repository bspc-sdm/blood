<script setup lang="ts">
// অ্যাপয়েন্টমেন্টের অনুরোধ (অ্যাপের BookDialog)
import { computed, ref } from 'vue';
import { Button, ChoiceChips, Dialog, ErrorText, IconLine, TextArea } from '@/ui/kit';
import { call, errorText } from '@/lib/api';
import { useCall } from '@/lib/query';
import { dateInputValue, fromDateInput } from '@/lib/format';
import { tr } from '@/lib/i18n';
import { DAY_MS, chamberLabel, chamberOpenOn, chamberTimeText, today, type FamilyMember, type Provider } from './model';
import { dayChip, nextOpenDays } from './doctors';
import { ONLINE_KEY, T as OT } from './online';
import { onlineOn } from './online/flags';
import { payOn } from '../pay/features';
import { getRequest, getRequestByRef } from '../pay/api';
import { pickRequest, type PayRequest } from '../pay/model';
import { T as PT } from '../pay/text';
import { money } from '../invoices/model';
import PayDialog from '../pay/PayDialog.vue';

const props = defineProps<{ provider: Provider; index: number }>();
const emit = defineEmits<{ close: []; booked: [] }>();
const ch = props.provider.chambers[props.index];
// চেম্বার বেছে এলে পরের বসার দিন আগে থেকে বাছা (বদলানো যায়) — এক চাপ কম
const date = ref<number | null>(nextOpenDays(ch, 1)[0] ?? null);
const note = ref('');
const memberId = ref('');
const busy = ref(false);
const err = ref<string | null>(null);
// কার জন্য: নিজে, অথবা পরিবারের সদস্য (যাঁর নিজের অ্যাকাউন্ট নেই)
const fam = useCall<{ family: FamilyMember[] }>('family_get');
const family = computed(() => fam.data?.family ?? []);
const quick = nextOpenDays(ch, 6);
// চেম্বারের অগ্রিম (pay_chamber_advance): আগে নিয়ম জানানো, বুকিংয়ের পর সাথে সাথে পেমেন্ট (না দিলে "অগ্রিম বাকি")
const advance = computed(() => (ch.mode !== 'online' && payOn('pay_chamber_advance') ? ch.advance ?? null : null));
const advReq = ref<PayRequest | null>(null);
async function advanceOf(r: any): Promise<PayRequest | null> {
  const got = pickRequest(r);
  if (got) return got;
  // book-এর উত্তরে payRequestId (PAYMENTS.md)
  if (r?.payRequestId) return getRequest(String(r.payRequestId)).catch(() => null);
  const id = r?.id ?? r?.appointmentId ?? r?.appointment?.id;
  return id ? getRequestByRef('appointment', String(id), 'booking_advance').catch(() => null) : null;
}
const t0 = today();

async function send() {
  const d = date.value;
  const e =
    d == null ? tr('Pick a date', 'তারিখ বেছে নিন')
    : d < t0 ? tr('Pick today or a later date', 'আজ বা পরের কোনো তারিখ দিন')
    : !chamberOpenOn(ch, d) ? tr('The chamber is closed that day. Pick one of its days.', 'সেদিন চেম্বার বন্ধ। চেম্বারের বারগুলোর একটা বেছে নিন।')
    : !note.value.trim() ? tr('Write the reason for the visit', 'কী সমস্যা, সংক্ষেপে লিখুন')
    : null;
  err.value = e;
  if (e || d == null) return;
  busy.value = true;
  try {
    const r = await call('book', {
      providerUid: props.provider.uid,
      chamberIndex: props.index,
      date: d,
      note: note.value.trim(),
      chamberKey: ch.mode === 'online' ? ONLINE_KEY : ch.hospitalId || ch.hospitalName,
      ...(memberId.value ? { memberId: memberId.value } : {}),
    });
    const req = advance.value ? await advanceOf(r) : null;
    if (req) advReq.value = req;
    else emit('booked');
  } catch (x) {
    err.value = errorText(x);
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <Dialog v-if="!advReq" :title="provider.name" @close="() => !busy && emit('close')">
    <div class="col appt-pre">
      <IconLine :icon="ch.mode === 'online' ? 'language' : 'local_hospital'">{{ chamberLabel(ch) }}</IconLine>
      <IconLine icon="schedule">{{ chamberTimeText(ch) }}</IconLine>
    </div>
    <p v-if="advance" class="t-body-s" data-testid="advance-line">{{ PT.advanceLine(money(advance.amount), advance.refundableUntilHoursBefore) }}</p>
    <p v-if="ch.mode === 'online' && !ch.noFee && onlineOn()" class="t-body-s muted" data-testid="online-book-hint">{{ OT.bookHint() }}</p>
    <div v-if="family.length" class="col gap-6">
      <span class="t-label-l">{{ tr('For whom', 'কার জন্য') }}</span>
      <ChoiceChips v-model="memberId" :options="[{ value: '', label: tr('Me', 'আমি') }, ...family.map((m) => ({ value: m.id, label: m.name }))]" />
    </div>
    <div class="col gap-6">
      <span class="t-label-l">{{ tr('Pick a date', 'তারিখ বেছে নিন') }}</span>
      <ChoiceChips v-if="quick.length" :options="quick.map((d) => ({ value: d, label: dayChip(d) }))" :model-value="date" @update:model-value="(v: number) => (date = v)" />
      <input
        type="date"
        class="input"
        :aria-label="tr('Pick a date', 'তারিখ বেছে নিন')"
        :min="dateInputValue(t0)"
        :max="dateInputValue(t0 + 60 * DAY_MS)"
        :value="date ? dateInputValue(date) : ''"
        @input="date = fromDateInput(($event.target as HTMLInputElement).value)"
      />
    </div>
    <TextArea :label="tr('Reason for visit', 'কী সমস্যা')" :rows="3" :model-value="note" @update:model-value="(v) => (note = v.slice(0, 300))" />
    <ErrorText :text="err" />
    <template #actions>
      <Button variant="text" :disabled="busy" @click="emit('close')">{{ tr('Cancel', 'বাতিল') }}</Button>
      <Button :busy="busy" @click="send">{{ tr('Send request', 'অনুরোধ পাঠান') }}</Button>
    </template>
  </Dialog>
  <PayDialog v-if="advReq" :initial="advReq" :title="PT.advanceAfterBook()" @close="emit('booked')" />
</template>
