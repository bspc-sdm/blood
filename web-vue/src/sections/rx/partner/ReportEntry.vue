<script setup lang="ts">
// রিপোর্ট দেওয়া (ছকের ঘর ভরা — শুধু ফল লিখুন; বেশি/কম নিজে থেকে), ছবি, নোট
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { Button, ConfirmDialog, ErrorText, SkeletonCards, Switch, TextArea, toast } from '@/ui/kit';
import { call, errorText } from '@/lib/api';
import { useCall } from '@/lib/query';
import { num, tr, trPlain } from '@/lib/i18n';
import Panel from './Panel.vue';
import { useInvalidate, useWide } from './common';
import { rangeText, type LabForm, type LabOrder, type LabResult } from './model';
import { flagOf, freeRow, highOf, lowOf, rowsFrom, type Row } from './labdetail';
import ReportPhotos from './ReportPhotos.vue';
import RowName from './entry/RowName.vue';
import RowResult from './entry/RowResult.vue';
import RowUnit from './entry/RowUnit.vue';
import RowRange from './entry/RowRange.vue';
import RowFlag from './entry/RowFlag.vue';

const props = defineProps<{ o: LabOrder }>();
const wide = useWide();
const invalidate = useInvalidate();
const form = useCall<{ forms: LabForm[] }>('lab_form', () => ({ id: props.o.id }), { staleTime: Infinity, retry: false });
const rows = ref<Row[] | null>(null);
const keep = ref(false);
const note = ref('');
const photos = ref<string[]>([]);
const busy = ref(false);
const err = ref<string | null>(null);
const confirming = ref(false);
const sent = ref(false);

// ছক এলে (না এলে প্রতিটা পরীক্ষার একটা খালি সারি) — একবারই
watch(
  () => [form.data, form.error, rows.value] as const,
  () => {
    if (rows.value) return;
    if (form.data) rows.value = rowsFrom(form.data.forms ?? []);
    else if (form.error) rows.value = props.o.tests.map((t) => freeRow(t));
  },
  { immediate: true },
);

const filled = computed(() => (rows.value ?? []).filter((r) => r.test.trim() && r.result.trim()));
const dirty = computed(() => filled.value.length > 0 || photos.value.length > 0 || !!note.value.trim());
const beforeUnload = (e: BeforeUnloadEvent) => {
  e.preventDefault();
  e.returnValue = '';
};
watch(dirty, (d, _o, onCleanup) => {
  if (!d) return;
  window.addEventListener('beforeunload', beforeUnload);
  onCleanup(() => window.removeEventListener('beforeunload', beforeUnload));
});
onBeforeUnmount(() => window.removeEventListener('beforeunload', beforeUnload));

const set = (id: number, patch: Partial<Row>) => (rows.value = (rows.value ?? []).map((r) => (r.id === id ? { ...r, ...patch } : r)));
const remove = (id: number) => (rows.value = (rows.value ?? []).filter((x) => x.id !== id));

function check(): string | null {
  const bad = (rows.value ?? []).find((r) => r.numeric && (Number.isNaN(lowOf(r)) || Number.isNaN(highOf(r)) || (lowOf(r) ?? -Infinity) > (highOf(r) ?? Infinity)));
  if (bad) return tr(`${bad.test}: check the normal range`, `${bad.test}: মাত্রা দেখে নিন`);
  if (!filled.value.length && !photos.value.length) return tr('Enter at least one result or a photo of the report', 'অন্তত একটা ফল বা রিপোর্টের ছবি দিন');
  return null;
}
async function send() {
  const all = rows.value ?? [];
  busy.value = true;
  err.value = null;
  try {
    if (keep.value) {
      const changed = all.filter((r) => r.fixed && r.numeric && r.code && r.key && (lowOf(r) !== r.base[0] || highOf(r) !== r.base[1]));
      if (changed.length) await call('lab_ranges_set', { ranges: changed.map((r) => ({ code: r.code, key: r.key, low: lowOf(r), high: highOf(r) })) });
    }
    const items: LabResult[] = filled.value.map((r) => {
      const lo = r.numeric ? lowOf(r) : null;
      const hi = r.numeric ? highOf(r) : null;
      return {
        test: r.test.trim(), result: r.result.trim(), unit: r.unit.trim(), range: r.numeric ? rangeText(lo, hi) : r.range.trim(), flag: flagOf(r),
        ...(r.group && r.code && r.key ? { group: r.group, code: r.code, key: r.key } : {}),
        ...(lo != null ? { low: lo } : {}), ...(hi != null ? { high: hi } : {}),
        ...(r.long ? { long: true } : {}),
      };
    });
    await call('lab_report', { id: props.o.id, items, note: note.value.trim(), photos: photos.value });
    confirming.value = false;
    sent.value = true;
    toast(tr('Report sent to the patient', 'রোগীর কাছে রিপোর্ট গেছে'));
    await invalidate('lab_orders', 'lab_order_get');
  } catch (e) {
    err.value = errorText(e);
    confirming.value = false;
  } finally {
    busy.value = false;
  }
}
function trySend() {
  const e = check();
  err.value = e;
  if (!e) confirming.value = true;
}

// পরীক্ষা ধরে ভাগ (CBC, লিপিড…); নিজে লেখা সারি শেষে
const groups = computed(() => {
  const out: { name: string; rows: Row[] }[] = [];
  for (const r of rows.value ?? []) {
    const g = r.fixed ? r.group : '';
    const last = out[out.length - 1];
    if (last && last.name === g) last.rows.push(r);
    else out.push({ name: g, rows: [r] });
  }
  return out;
});
const hasFixed = computed(() => (rows.value ?? []).some((r) => r.fixed));
const hasNumeric = computed(() => (rows.value ?? []).some((r) => r.numeric));
const confirmText = computed(() =>
  trPlain(
    `It goes to ${props.o.patientName || 'the patient'} right away${filled.value.some((r) => flagOf(r)) ? ', with high/low marked' : ''}. It can't be changed after sending.`,
    `${props.o.patientName || 'রোগী'}-এর কাছে সাথে সাথে যাবে${filled.value.some((r) => flagOf(r)) ? ' (বেশি/কম চিহ্নসহ)' : ''}। পাঠানোর পর আর বদলানো যায় না।`,
  ),
);
</script>

<template>
  <SkeletonCards v-if="!rows || sent" :n="1" :h="320" />
  <Panel v-else :title="tr('Report', 'রিপোর্ট')" icon="edit_note">
    <div class="q-sticky-top" data-testid="report-send-top">
      <span class="t-body-s muted">{{ filled.length ? tr(`${filled.length} results written`, `${num(filled.length)}টা ফল লেখা হয়েছে`) : '' }}</span>
      <Button icon="send" :busy="busy" @click="trySend">{{ tr('Send report', 'রিপোর্ট পাঠান') }}</Button>
    </div>
    <p v-if="hasFixed" class="t-body-s muted">
      {{ tr('Rows, units and normal ranges are filled in — just write the results. High/low is marked by itself. Change a range if your machine is different.', 'ঘর, একক আর স্বাভাবিক মাত্রা ভরা আছে — শুধু ফল লিখুন। বেশি/কম নিজে থেকে বসবে। আপনার মেশিনের মাত্রা আলাদা হলে বদলে নিন।') }}
    </p>
    <p v-if="form.error" class="t-body-s muted">{{ tr('Couldn\'t load the form — write the results yourself.', 'ছক আনা যায়নি — ফল নিজে লিখুন।') }}</p>

    <div v-if="wide" class="col gap-12">
      <div v-for="(g, gi) in groups" :key="gi" class="table-wrap rxp-entry">
        <div v-if="g.name" class="rxp-group">{{ g.name }}</div>
        <table class="table rxp-table">
          <thead>
            <tr>
              <th class="rxp-col-test">{{ tr('Test', 'পরীক্ষা') }}</th>
              <th class="rxp-col-result">{{ tr('Result', 'ফল') }}</th>
              <th class="rxp-col-unit">{{ tr('Unit', 'একক') }}</th>
              <th class="rxp-col-range">{{ tr('Normal range', 'স্বাভাবিক মাত্রা') }}</th>
              <th class="rxp-col-flag">{{ tr('High/low', 'বেশি/কম') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="r in g.rows" :key="r.id">
              <td><RowName :r="r" :set="set" :remove="remove" /></td>
              <td v-if="r.long" colspan="4"><RowResult :r="r" :set="set" /></td>
              <template v-else>
                <td><RowResult :r="r" :set="set" /></td>
                <td><RowUnit :r="r" :set="set" /></td>
                <td><RowRange :r="r" :set="set" /></td>
                <td><RowFlag :r="r" :set="set" /></td>
              </template>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
    <div v-else class="col gap-8">
      <template v-for="(g, gi) in groups" :key="gi">
        <div v-if="g.name" class="rxp-group flat">{{ g.name }}</div>
        <div v-for="r in g.rows" :key="r.id" class="rxp-entry-card">
          <div class="row">
            <div class="grow"><RowName :r="r" :set="set" :remove="remove" /></div>
            <RowFlag v-if="r.numeric" :r="r" :set="set" />
          </div>
          <div class="row gap-8">
            <div class="grow"><RowResult :r="r" :set="set" /></div>
            <RowUnit v-if="!r.long" :r="r" :set="set" />
          </div>
          <div v-if="!r.long" class="row wrap gap-8">
            <span class="t-body-s muted">{{ tr('Normal', 'স্বাভাবিক') }}</span>
            <div class="grow"><RowRange :r="r" :set="set" /></div>
            <RowFlag v-if="!r.numeric" :r="r" :set="set" />
          </div>
        </div>
      </template>
    </div>
    <div class="row">
      <Button variant="text" small icon="add" @click="rows = [...rows, freeRow()]">{{ tr('Add a row', 'আরেকটা সারি') }}</Button>
    </div>
    <Switch v-if="hasNumeric" v-model="keep" :label="tr('Keep changed ranges for my lab', 'বদলানো মাত্রা আমার ল্যাবের জন্য রাখুন')" :sub="tr('The next report form will have these', 'পরের রিপোর্টে এটাই আসবে')" />
    <div class="rxp-two">
      <TextArea v-model="note" :label="tr('Note (optional)', 'নোট (ঐচ্ছিক)')" maxlength="500" :rows="3" />
      <ReportPhotos :photos="photos" @change="(p) => (photos = p)" />
    </div>
    <ErrorText :text="err" />
    <!-- "রিপোর্ট পাঠান" সবসময় হাতের কাছে (আমার কাজ CONTRACT §২): ফোনে নিচে আটকানো, ডেস্কটপে ডানে-উপরে (q-sticky-top) -->
    <div class="q-sticky-bar" data-testid="report-send-bar">
      <span class="t-body-s muted grow">{{ filled.length ? tr(`${filled.length} results written`, `${num(filled.length)}টা ফল লেখা হয়েছে`) : '' }}</span>
      <Button icon="send" :busy="busy" @click="trySend">{{ tr('Send report', 'রিপোর্ট পাঠান') }}</Button>
    </div>
    <ConfirmDialog v-if="confirming" :title="tr('Send the report?', 'রিপোর্ট পাঠাবেন?')" :text="confirmText" :confirm="tr('Send', 'পাঠান')" :busy="busy" @confirm="send" @close="confirming = false" />
  </Panel>
</template>
