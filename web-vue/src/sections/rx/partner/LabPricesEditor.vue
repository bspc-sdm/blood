<script setup lang="ts">
// পরীক্ষা ও দাম — সম্পাদনা
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { toast } from '@/ui/kit';
import { call, errorText } from '@/lib/api';
import { tr } from '@/lib/i18n';
import { useInvalidate, useWide } from './common';
import { parseNum, type LabPriceList, type TestDef } from './model';
import { nextId, norm, priceOf, rowOf, type PriceRow } from './labprices';
import AddTests from './AddTests.vue';
import PriceListPanel from './PriceListPanel.vue';
import PriceSettingsPanel from './PriceSettingsPanel.vue';

const props = defineProps<{ data: LabPriceList; tests: TestDef[] }>();
const wide = useWide();
const invalidate = useInvalidate();
const rows = ref<PriceRow[]>(props.data.items.map(rowOf));
const disc = ref(props.data.discountPct ? String(props.data.discountPct) : '');
const fee = ref(props.data.homeFee != null ? String(props.data.homeFee) : '');
// ছকে লুকানো সারি থাকলে সেভ চালু — সেভে সেগুলো নিজে লেখা হিসেবে থেকে যায়
const dirty = ref(props.data.items.some((i) => i.hidden));
const busy = ref(false);
const err = ref<string | null>(null);
const tried = ref(false);
const bn = computed(() => new Map(props.tests.map((t) => [t.code, t.bn ?? ''])));

const beforeUnload = (e: BeforeUnloadEvent) => {
  e.preventDefault();
  e.returnValue = '';
};
watch(
  dirty,
  (d, _o, onCleanup) => {
    if (!d) return;
    window.addEventListener('beforeunload', beforeUnload);
    onCleanup(() => window.removeEventListener('beforeunload', beforeUnload));
  },
  { immediate: true },
);
onBeforeUnmount(() => window.removeEventListener('beforeunload', beforeUnload));

function change(next: PriceRow[]) {
  rows.value = next;
  dirty.value = true;
}
const set = (id: number, patch: Partial<PriceRow>) => change(rows.value.map((r) => (r.id === id ? { ...r, ...patch } : r)));
const has = (code: string | null, name: string) => rows.value.some((r) => (code ? r.code === code : !r.code && norm(r.name) === norm(name)));
function add(code: string | null, name: string) {
  if (has(code, name)) return;
  change([{ id: nextId(), code, name, price: '', tat: '', on: true }, ...rows.value]);
  setTimeout(() => document.querySelector<HTMLInputElement>('[data-lp-price]')?.focus(), 30);
}

async function save() {
  tried.value = true;
  const bad = rows.value.find((r) => priceOf(r.price) == null || Number.isNaN(priceOf(r.price)));
  if (bad) {
    err.value = priceOf(bad.price) == null ? tr(`${bad.name}: write the price`, `${bad.name}: দাম লিখুন`) : tr(`${bad.name}: check the price`, `${bad.name}: দাম দেখে নিন`);
    return;
  }
  const d = parseNum(disc.value);
  const h = parseNum(fee.value);
  if (Number.isNaN(d) || (d != null && (d < 0 || d > 90 || !Number.isInteger(d)))) {
    err.value = tr('Discount must be 0–90%', 'ছাড় ০–৯০% এর মধ্যে দিন');
    return;
  }
  if (Number.isNaN(h) || (h != null && (h < 0 || h > 5000 || !Number.isInteger(h)))) {
    err.value = tr('Check the home collection fee', 'বাসা থেকে নমুনার চার্জ দেখে নিন');
    return;
  }
  busy.value = true;
  err.value = null;
  try {
    // ছকে লুকানো পরীক্ষা: কোডের সাথে নামও — সার্ভার নাম দিয়ে নিজে লেখা হিসেবে রাখে (সেভ আটকায় না)
    const out = await call<LabPriceList>('lab_prices_set', {
      items: rows.value.map((r) => ({ ...(r.code ? { code: r.code, ...(r.hidden ? { name: r.name } : {}) } : { name: r.name }), price: priceOf(r.price), tat: r.tat.trim(), on: r.on })),
      discountPct: d, homeFee: props.data.homeCollection ? h : null,
    });
    dirty.value = false;
    toast(
      out.kept?.length
        ? tr(`Saved. ${out.kept.join(', ')} is no longer in the test list — kept as your own test`, `সেভ হয়েছে। ${out.kept.join(', ')} এখন পরীক্ষার ছকে নেই — নিজে লেখা পরীক্ষা হিসেবে রাখা হলো`)
        : tr('Saved — patients see these prices now', 'সেভ হয়েছে — রোগীরা এখন এই দাম দেখবেন'),
    );
    await invalidate('lab_prices_get');
  } catch (e) {
    err.value = errorText(e);
  } finally {
    busy.value = false;
  }
}

const remove = (r: PriceRow) => change(rows.value.filter((x) => x.id !== r.id));
const setDisc = (v: string) => {
  disc.value = v;
  dirty.value = true;
};
const setFee = (v: string) => {
  fee.value = v;
  dirty.value = true;
};
</script>

<template>
  <!-- চওড়া পর্দা: বাঁয়ে তালিকা, ডানে যোগ আর ছাড়; মোবাইলে খোঁজা → তালিকা → ছাড় -->
  <div class="rxp-grid">
    <template v-if="wide">
      <div class="rxp-main">
        <PriceListPanel :rows="rows" :wide="wide" :tried="tried" :bn="bn" :err="err" :busy="busy" :dirty="dirty" :data="data" @set="set" @remove="remove" @save="save" />
      </div>
      <div class="rxp-side col gap-16">
        <AddTests :tests="tests" :has="has" :suggest="wide" @add="add" />
        <PriceSettingsPanel :disc="disc" :fee="fee" :data="data" @disc="setDisc" @fee="setFee" />
      </div>
    </template>
    <template v-else>
      <AddTests :tests="tests" :has="has" :suggest="wide" @add="add" />
      <PriceListPanel :rows="rows" :wide="wide" :tried="tried" :bn="bn" :err="err" :busy="busy" :dirty="dirty" :data="data" @set="set" @remove="remove" @save="save" />
      <PriceSettingsPanel :disc="disc" :fee="fee" :data="data" @disc="setDisc" @fee="setFee" />
    </template>
  </div>
</template>
