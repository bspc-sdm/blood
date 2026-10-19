<script setup lang="ts">
// "যা দিলাম লিখে রাখুন"-এর পর: এই ওষুধগুলো বিক্রির হিসাবে তোলার প্রস্তাব (দাম পণ্যের তালিকা থেকে, বদলানো যায়)।
// না চাইলে "এখন না" — ওষুধ দেওয়া আটকায় না। DispenseForm-এ এক লাইন: <RxSaleOffer :rx="rx" />
import { computed, ref, watch } from 'vue';
import { Button, ErrorText, Loading, toast } from '@/ui/kit';
import { call, errorText } from '@/lib/api';
import { tr } from '@/lib/i18n';
import type { PharmRx } from '../model';
import { money, numInput, numStr, qtyText, r2, readNum, sourceText, useBooksRefresh, type RxPreview } from './model';

const props = defineProps<{ rx: PharmRx }>();
const lastOf = () => props.rx.dispensed[props.rx.dispensed.length - 1]?.at ?? 0;
// খোলার সময় যেটা শেষ ছিল — এর পরে নতুন ভাগ এলে তবেই প্রস্তাব
const base = lastOf();
const closed = ref<number | null>(null);
const at = computed(() => {
  const last = lastOf();
  return last && last !== base && closed.value !== last ? last : null;
});
const pv = ref<RxPreview | null>(null);
const price = ref<Record<number, string>>({});
const busy = ref(false);
const err = ref<string | null>(null);
const refresh = useBooksRefresh();

watch(
  () => [at.value, props.rx.code] as const,
  ([a, code], _o, onCleanup) => {
    if (!a) return;
    let live = true;
    pv.value = null;
    err.value = null;
    call<RxPreview>('books_rx_preview', { code, at: a })
      .then((r) => {
        if (!live) return;
        if (r.recorded) {
          closed.value = a;
          return;
        }
        pv.value = r;
        price.value = Object.fromEntries(r.lines.map((l) => [l.i, numStr(l.unitPrice != null ? r2(l.unitPrice - l.unitDisc) : null)]));
      })
      .catch(() => {
        if (live) closed.value = a;
      });
    onCleanup(() => (live = false));
  },
  { immediate: true },
);

const bad = computed(() => Object.values(price.value).some((s) => Number.isNaN(readNum(s))));
const total = computed(() => (pv.value ? r2(pv.value.lines.reduce((a, l) => a + (readNum(price.value[l.i] ?? '') || 0) * l.qty, 0)) : 0));
async function record() {
  const p0 = pv.value;
  if (!p0 || bad.value) return;
  busy.value = true;
  err.value = null;
  try {
    await call('books_sale_add', {
      source: 'rx', rx: { code: p0.code, at: p0.at },
      lines: p0.lines.map((l) => {
        const p = readNum(price.value[l.i] ?? '');
        // দাম না বদলালে পণ্যের MRP আর ছাড় আলাদা থাকে (হিসাবে "ছাড় দেওয়া" ঠিক আসে)
        const same = l.unitPrice != null && p === r2(l.unitPrice - l.unitDisc);
        return { i: l.i, ...(l.itemId ? { itemId: l.itemId } : {}), unitPrice: same ? l.unitPrice : p, unitDisc: same ? l.unitDisc : 0 };
      }),
    });
    toast(tr('Added to sales', 'বিক্রির হিসাবে উঠেছে'));
    closed.value = p0.at;
    refresh();
  } catch (e) {
    err.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <template v-if="at">
    <Loading v-if="!pv" />
    <div v-else class="bk-offer">
      <div class="row">
        <span class="t-title-s grow">{{ tr('Add to sales?', 'বিক্রির হিসাবে তুলবেন?') }}</span>
        <span class="t-body-s muted">{{ sourceText(pv.source) }}</span>
      </div>
      <div class="col gap-6">
        <div v-for="l in pv.lines" :key="l.i" class="row gap-8">
          <span class="grow ellipsis">{{ l.itemName || l.name }} <span class="muted num">×{{ qtyText(l.qty) }}</span></span>
          <input
            class="input num bk-small-input"
            inputmode="decimal"
            :value="price[l.i] ?? ''"
            :placeholder="tr('Price', 'দাম')"
            :aria-label="`${l.name}: ${tr('price per unit', 'প্রতি এককের দাম')}`"
            @input="(e) => { const t = e.target as HTMLInputElement; price = { ...price, [l.i]: numInput(t.value) }; t.value = price[l.i]; }"
          />
        </div>
      </div>
      <ErrorText :text="err" />
      <div class="row wrap gap-8">
        <span class="grow semi num">{{ tr('Total ', 'মোট ') }}{{ money(total) }}</span>
        <Button variant="text" @click="closed = pv.at">{{ tr('Not now', 'এখন না') }}</Button>
        <Button variant="tonal" icon="payments" :busy="busy" :disabled="bad" @click="record">{{ tr('Add to sales', 'হিসাবে তুলুন') }}</Button>
      </div>
    </div>
  </template>
</template>
