<script setup lang="ts">
// চেম্বারের ফি নেওয়া (chamber_fee_collect): সিরিয়ালের রোগী (appointmentId) বা সরাসরি আসা রোগী (walkIn: নাম, ফোন, চেম্বার, আজ)।
// দেখানোর ধরন (নতুন / ফলো-আপ / রিপোর্ট দেখানো) — ফি আগে বসানো (চেম্বারের ফি বা শেষবার যা নিলেন), বদলানো যায়; মাধ্যম; নগদে ফেরত।
import { computed, ref, watch } from 'vue';
import { Button, Dialog, ErrorText, Segmented, Select, TextInput, inputValue, toast } from '@/ui/kit';
import { num, tr } from '@/lib/i18n';
import { store as kv } from '@/lib/store';
import { numInput, readNum } from '@/sections/rx/partner/books/model';
import { T } from './text';
import PayFields from './PayFields.vue';
import { money, todayStr, type Invoice, type PayMethod } from './model';
import { errText, issue, useInvoiceRefresh } from './actions';
import type { FeeData } from '@/offline';

export type VisitType = 'new' | 'followup' | 'report';
export interface DeskChamber { chamberKey: string; name: string; fees?: Partial<Record<VisitType, number>> | null }
const props = defineProps<{
  bizId: string;
  doctorUid: string | null;
  chambers: DeskChamber[];
  row?: { id: string; name: string; serial: number; chamberKey: string } | null;
}>();
const emit = defineEmits<{ close: []; done: [inv: Invoice | null] }>();
const refresh = useInvoiceRefresh();
const KEY = `lf.deskFee.${props.bizId}`;
const remembered = kv.getJson<Partial<Record<VisitType, number>>>(KEY) ?? {};

const name = ref('');
const phone = ref('');
const chamberKey = ref(props.row?.chamberKey ?? props.chambers[0]?.chamberKey ?? '');
const visit = ref<VisitType>('new');
const amount = ref('');
const method = ref<PayMethod>('cash');
const given = ref('');
const txRef = ref('');
const busy = ref(false);
const err = ref<string | null>(null);
const ch = computed(() => props.chambers.find((c) => c.chamberKey === chamberKey.value) ?? null);
watch(
  [visit, chamberKey],
  () => {
    const v = ch.value?.fees?.[visit.value] ?? remembered[visit.value];
    amount.value = v != null ? String(v) : '';
  },
  { immediate: true },
);
const a = computed(() => readNum(amount.value));
const ok = computed(() => a.value != null && !Number.isNaN(a.value) && (!!props.row || name.value.trim().length >= 2) && !!chamberKey.value);
async function save() {
  if (!ok.value || busy.value) return;
  busy.value = true;
  err.value = null;
  try {
    const target = props.row
      ? { appointmentId: props.row.id }
      : { walkIn: { name: name.value.trim(), ...(phone.value.trim() ? { phone: phone.value.trim() } : {}), chamberKey: chamberKey.value, date: todayStr() } };
    const data: FeeData = { ...target, visitType: visit.value, amount: a.value!, method: method.value, ...(txRef.value.trim() ? { ref: txRef.value.trim() } : {}) };
    // সহকারী: কোন ডাক্তারের হয়ে (সার্ভারের chamber_fee_collect doctorUid নেয়)
    const r = await issue('chamber_fee_collect', { ...data, ...(props.doctorUid ? { doctorUid: props.doctorUid } : {}) }, () => ({ type: 'fee', data }));
    kv.setJson(KEY, { ...remembered, [visit.value]: a.value });
    toast(T.feeTaken() + ' · ' + money(a.value));
    refresh();
    emit('done', r.invoice);
  } catch (e) {
    err.value = errText(e);
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <Dialog size="small" :title="row ? T.collectFee() : T.addWalkIn()" @close="() => !busy && emit('close')">
    <span v-if="row" class="t-title-m">{{ tr('Serial #', 'সিরিয়াল নং ') + num(row.serial) }} · {{ row.name }}</span>
    <template v-else>
      <TextInput v-model="name" :label="tr('Name', 'নাম')" maxlength="60" data-testid="walkin-name" />
      <TextInput :model-value="phone" :label="T.customerPhone()" inputmode="tel" maxlength="14" @update:model-value="(v) => (phone = v.replace(/[^0-9+০-৯]/g, ''))" />
    </template>
    <Select v-if="chambers.length > 1" v-model="chamberKey" :label="T.chamber()" :options="chambers.map((c) => ({ value: c.chamberKey, label: c.name }))" />
    <div class="col gap-4">
      <span class="t-label-m muted">{{ T.visitType() }}</span>
      <Segmented v-model="visit" :label="T.visitType()" :options="[{ value: 'new', label: T.visitNew() }, { value: 'followup', label: T.visitFollowup() }, { value: 'report', label: T.visitReport() }]" />
    </div>
    <label class="bk-cell">
      <span class="t-label-s muted">{{ T.fee() }}</span>
      <input class="input num" inputmode="decimal" :value="amount" placeholder="০" data-testid="fee-amount" @input="amount = inputValue($event, numInput)" />
    </label>
    <PayFields v-model:method="method" v-model:given="given" v-model:tx-ref="txRef" :total="a ?? 0" />
    <ErrorText :text="err" />
    <template #actions>
      <Button variant="text" :disabled="busy" @click="emit('close')">{{ tr('Cancel', 'বাতিল') }}</Button>
      <Button icon="check_circle" :busy="busy" :disabled="!ok" data-testid="fee-save" @click="save">{{ T.collectFee() }}</Button>
    </template>
  </Dialog>
</template>
