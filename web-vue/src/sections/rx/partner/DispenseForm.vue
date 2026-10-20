<script setup lang="ts">
// খোলা প্রেসক্রিপশনে কয়টা দিলেন (পুরো বা কিছু), "নেই" চিহ্ন
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { useQueryClient } from '@tanstack/vue-query';
import { Button, Check, ErrorText, Icon, InfoText, Tag, cx, toast } from '@/ui/kit';
import { call, errorText } from '@/lib/api';
import { num, toAsciiDigits, tr } from '@/lib/i18n';
import { formatDayOf } from '@/lib/format';
import { focusNext, useInvalidate, useWide } from './common';
import { ageText, boughtText, doseText, medTitle, timingText, type PharmMed, type PharmRx } from './model';
import RxSaleOffer from './books/RxSaleOffer.vue';

const props = defineProps<{ start: PharmRx }>();
const emit = defineEmits<{ next: [] }>();
const wide = useWide();
const qc = useQueryClient();
const invalidate = useInvalidate();
const rx = ref<PharmRx>(props.start);
const qty = ref<Record<number, string>>({});
const none = ref<number[]>([]);
const busy = ref(false);
const err = ref<string | null>(null);
const info = ref<string | null>(null);
const cancelled = computed(() => rx.value.status === 'cancelled');
const open = computed(() => rx.value.medicines.filter((m) => !m.done));
const qn = (i: number) => parseInt(toAsciiDigits(qty.value[i] ?? ''), 10) || 0;
const can = computed(() => !cancelled.value && (open.value.some((m) => qn(m.i) > 0) || none.value.length > 0));

// প্রথম "কয়টা" ঘরে ফোকাস — কাউন্টারে সরাসরি লেখা শুরু
const box = ref<HTMLElement | null>(null);
let t: ReturnType<typeof setTimeout> | undefined;
onMounted(() => {
  t = setTimeout(() => box.value?.querySelector<HTMLInputElement>('[data-rxp-nav="qty"]')?.focus(), 60);
});
onBeforeUnmount(() => clearTimeout(t));

async function save() {
  if (!can.value || busy.value) return;
  const items = open.value.filter((m) => qn(m.i) > 0 && !none.value.includes(m.i)).map((m) => ({ i: m.i, qty: qn(m.i) }));
  const over = open.value.find((m) => m.left != null && qn(m.i) > m.left);
  if (over) {
    err.value = tr(`${over.name}: only ${over.left} left on this prescription`, `${over.name}: এই প্রেসক্রিপশনে আর ${num(over.left ?? 0)}টা বাকি`);
    return;
  }
  busy.value = true;
  err.value = null;
  info.value = null;
  try {
    const code = rx.value.code;
    const r = await call<{ item: PharmRx }>('rx_dispense', { code, items, missing: none.value });
    rx.value = r.item;
    qc.setQueriesData({ predicate: (q) => q.queryKey[0] === 'rx_pharmacy_open' && (q.queryKey[1] as { code?: string })?.code === code }, { item: r.item });
    qty.value = {};
    none.value = [];
    const msg = items.length ? tr('Saved', 'লেখা হয়েছে') : tr('The patient was told', 'রোগীকে জানানো হয়েছে');
    info.value = msg;
    toast(msg);
    invalidate('rx_orders_pharmacy', 'rx_pharmacy_home');
  } catch (e) {
    err.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
function setQty(m: PharmMed, e: Event) {
  const el = e.target as HTMLInputElement;
  const v = toAsciiDigits(el.value).replace(/\D/g, '').slice(0, 4);
  qty.value = { ...qty.value, [m.i]: v };
  if (el.value !== v) el.value = v;
}
function qtyKey(e: KeyboardEvent) {
  if (e.key === 'Enter') {
    e.preventDefault();
    if (e.ctrlKey || e.metaKey) save();
    else focusNext(e.currentTarget as HTMLElement, 'qty', save);
  }
}
function setNone(m: PharmMed, v: boolean) {
  none.value = v ? [...none.value, m.i] : none.value.filter((x) => x !== m.i);
  if (v) {
    const rest = { ...qty.value };
    delete rest[m.i];
    qty.value = rest;
  }
}
const givenLine = (d: PharmRx['dispensed'][number]) => d.items.map((it) => `${rx.value.medicines[it.i]?.name ?? ''} ×${num(it.qty)}`).join(', ');
</script>

<template>
  <section ref="box" class="card rxp-panel rxp-rx">
    <div v-if="cancelled" class="rxp-alert"><Icon name="warning" /><span>{{ tr('Cancelled prescription — do not dispense', 'বাতিল করা প্রেসক্রিপশন — ওষুধ দেবেন না') }}</span></div>
    <div class="rxp-rx-head">
      <div class="col gap-4 grow">
        <span class="t-title-m">{{ rx.patient.name || '—' }}<span v-if="ageText(rx.patient.age)" class="muted t-body-m"> · {{ ageText(rx.patient.age) }}</span></span>
        <span class="t-body-m">{{ rx.doctor.name }}<span v-if="rx.doctor.degrees" class="muted"> · {{ rx.doctor.degrees }}</span><span v-if="rx.doctor.regNo" class="muted"> · BMDC {{ rx.doctor.regNo }}</span></span>
        <span class="t-body-s muted">{{ formatDayOf(rx.createdAt) }}</span>
      </div>
      <span class="rxp-code-chip num" :aria-label="tr('Prescription code', 'প্রেসক্রিপশনের কোড')">{{ rx.code }}</span>
    </div>

    <div v-if="wide" class="table-wrap rxp-table-wrap">
      <table class="table rxp-table">
        <thead>
          <tr>
            <th>{{ tr('Medicine', 'ওষুধ') }}</th>
            <th>{{ tr('Dose', 'নিয়ম') }}</th>
            <th>{{ tr('Bought', 'কেনা') }}</th>
            <th>{{ tr('Given now', 'এখন দিলেন') }}</th>
            <th><span class="sr-only">{{ tr('Don\'t have it', 'নেই') }}</span></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="m in rx.medicines" :key="m.i" :class="cx(m.done && 'rxp-done', none.includes(m.i) && 'rxp-none')">
            <td>
              <div class="col gap-2">
                <span class="semi">{{ medTitle(m) }}<span v-if="m.strength" class="muted"> · {{ m.strength }}</span></span>
                <span v-if="m.timing || m.note" class="t-body-s muted">{{ [timingText(m.timing), m.note].filter(Boolean).join(' · ') }}</span>
              </div>
            </td>
            <td class="num rxp-nowrap">{{ doseText(m) }}</td>
            <td><span :class="cx('t-body-s', m.done ? 'c-primary' : 'muted')">{{ boughtText(m) }}{{ m.left != null && !m.done ? tr(` · ${m.left} left`, ` · বাকি ${num(m.left)}`) : '' }}</span></td>
            <td>
              <Tag v-if="m.done" tone="good">{{ tr('All given', 'দেওয়া শেষ') }}</Tag>
              <span v-else-if="cancelled" class="muted">—</span>
              <div v-else class="rxp-qty">
                <input
                  class="input rxp-qty-input num"
                  inputmode="numeric"
                  data-rxp-nav="qty"
                  :disabled="none.includes(m.i)"
                  :aria-label="`${m.name}: ${tr('how many given', 'কয়টা দিলেন')}`"
                  :placeholder="m.left != null ? tr(`${m.left} left`, `বাকি ${num(m.left)}`) : tr('Qty', 'কয়টা')"
                  :value="qty[m.i] ?? ''"
                  @input="setQty(m, $event)"
                  @keydown="qtyKey"
                />
                <button v-if="m.left != null && m.left > 0 && !none.includes(m.i)" type="button" class="rxp-full" :title="tr('Give all that is left', 'বাকি সবটা দিন')" @click="qty = { ...qty, [m.i]: String(m.left) }">
                  {{ tr('All', 'পুরো') }}
                </button>
              </div>
            </td>
            <td>
              <Check v-if="!(m.done || cancelled)" :model-value="none.includes(m.i)" @update:model-value="(v) => setNone(m, v)">{{ tr('Don\'t have it', 'নেই') }}</Check>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <div v-else class="col gap-8">
      <div v-for="m in rx.medicines" :key="m.i" :class="cx('rxp-med', m.done && 'rxp-done', none.includes(m.i) && 'rxp-none')">
        <div class="row top">
          <div class="col gap-2 grow">
            <div class="col gap-2">
              <span class="semi">{{ medTitle(m) }}<span v-if="m.strength" class="muted"> · {{ m.strength }}</span></span>
              <span v-if="m.timing || m.note" class="t-body-s muted">{{ [timingText(m.timing), m.note].filter(Boolean).join(' · ') }}</span>
            </div>
            <span class="t-body-s num">{{ doseText(m) }}</span>
            <span :class="cx('t-body-s', m.done ? 'c-primary' : 'muted')">{{ boughtText(m) }}{{ m.left != null && !m.done ? tr(` · ${m.left} left`, ` · বাকি ${num(m.left)}`) : '' }}</span>
          </div>
          <Tag v-if="m.done" tone="good">{{ tr('All given', 'দেওয়া শেষ') }}</Tag>
          <span v-else-if="cancelled" class="muted">—</span>
          <div v-else class="rxp-qty">
            <input
              class="input rxp-qty-input num"
              inputmode="numeric"
              data-rxp-nav="qty"
              :disabled="none.includes(m.i)"
              :aria-label="`${m.name}: ${tr('how many given', 'কয়টা দিলেন')}`"
              :placeholder="m.left != null ? tr(`${m.left} left`, `বাকি ${num(m.left)}`) : tr('Qty', 'কয়টা')"
              :value="qty[m.i] ?? ''"
              @input="setQty(m, $event)"
              @keydown="qtyKey"
            />
            <button v-if="m.left != null && m.left > 0 && !none.includes(m.i)" type="button" class="rxp-full" :title="tr('Give all that is left', 'বাকি সবটা দিন')" @click="qty = { ...qty, [m.i]: String(m.left) }">
              {{ tr('All', 'পুরো') }}
            </button>
          </div>
        </div>
        <Check v-if="!(m.done || cancelled)" :model-value="none.includes(m.i)" @update:model-value="(v) => setNone(m, v)">{{ tr('Don\'t have it', 'নেই') }}</Check>
      </div>
    </div>

    <p v-if="none.length" class="t-body-s muted">{{ tr('The patient will be told, and it goes on your "asked for but not available" list.', 'রোগী জানবেন, আর ওষুধটা আপনার "চেয়েও পাননি" তালিকায় জমা হবে।') }}</p>
    <div v-if="rx.dispensed.length" class="col gap-4">
      <span class="t-label-l">{{ tr('Given before', 'আগে দেওয়া') }}</span>
      <span v-for="(d, i) in rx.dispensed" :key="i" class="t-body-s muted">{{ d.name }} · {{ formatDayOf(d.at) }} · {{ givenLine(d) }}</span>
    </div>
    <ErrorText :text="err" />
    <InfoText :text="info" />
    <RxSaleOffer :rx="rx" />
    <div class="row wrap gap-8 rxp-actions">
      <Button v-if="!cancelled && open.length" icon="check_circle" :busy="busy" :disabled="!can" title="Ctrl + Enter" @click="save">
        {{ tr('Save what I gave', 'যা দিলাম লিখে রাখুন') }}
      </Button>
      <Button variant="text" icon="add" @click="emit('next')">{{ tr('Next patient', 'পরের রোগী') }}</Button>
      <span v-if="wide && !cancelled && open.length" class="t-body-s muted rxp-kbd">{{ tr('Enter: next box · Ctrl+Enter: save', 'Enter: পরের ঘর · Ctrl+Enter: সেভ') }}</span>
    </div>
  </section>
</template>
