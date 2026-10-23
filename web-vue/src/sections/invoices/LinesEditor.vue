<script setup lang="ts">
// বিলের লাইন (নাম, কয়টা, দর, ছাড় — লাইনের মোট ছাড় টাকায়) — অনলাইন অর্ডারের রসিদ আর প্যাথলজির বিলে।
// নাম বদলানো যায় না যদি তালিকা থেকে আসে (fixed); খালি লাইন যোগ করা যায় (addable)।
import { IconButton, inputValue } from '@/ui/kit';
import { tr } from '@/lib/i18n';
import { numInput, readNum } from '@/sections/rx/partner/books/model';
import { T } from './text';
import { money, r2, type DraftLine } from './model';

export interface Row { key: number; name: string; qty: string; price: string; disc: string; code?: string | null; itemId?: string | null; fixed?: boolean }
const rows = defineModel<Row[]>({ required: true });
defineProps<{ noQty?: boolean }>();
const set = (k: number, p: Partial<Row>) => (rows.value = rows.value.map((r) => (r.key === k ? { ...r, ...p } : r)));
const total = (r: Row) => {
  const q = readNum(r.qty) ?? 1;
  const p = readNum(r.price);
  if (p == null || Number.isNaN(p) || Number.isNaN(q)) return '—';
  return money(r2(Math.max(0, q * p - (readNum(r.disc) || 0))));
};
</script>

<script lang="ts">
let seq = 0;
export const rowKey = () => ++seq;
/** সার্ভারে পাঠানোর লাইন (নাম আর দাম থাকলে) */
export function linesOf(rows: { name: string; qty: string; price: string; disc: string; code?: string | null; itemId?: string | null }[]): (DraftLine & { code?: string; itemId?: string })[] {
  return rows
    .map((r) => ({ name: r.name.trim(), qty: readNum(r.qty) ?? 1, price: readNum(r.price) ?? NaN, disc: readNum(r.disc) || 0, ...(r.code ? { code: r.code } : {}), ...(r.itemId ? { itemId: r.itemId } : {}) }))
    .filter((l) => l.name && Number.isFinite(l.price) && Number.isFinite(l.qty) && l.qty > 0);
}
</script>

<template>
  <div class="col gap-8">
    <div v-for="r in rows" :key="r.key" class="bk-line inv-line">
      <label class="col gap-2 bk-line-name">
        <span v-if="r.fixed" class="semi">{{ r.name }}</span>
        <template v-else>
          <span class="t-label-s muted">{{ T.item() }}</span>
          <input class="input" :value="r.name" maxlength="80" data-testid="line-name" @input="set(r.key, { name: ($event.target as HTMLInputElement).value })" />
        </template>
      </label>
      <label v-if="!noQty" class="bk-cell"><span class="t-label-s muted">{{ tr('Qty', 'কয়টা') }}</span>
        <input class="input num" inputmode="decimal" :value="r.qty" data-testid="line-qty" @input="set(r.key, { qty: inputValue($event, numInput) })" />
      </label>
      <label class="bk-cell"><span class="t-label-s muted">{{ tr('Price', 'দাম') }}</span>
        <input class="input num" inputmode="decimal" :value="r.price" placeholder="—" data-testid="line-price" @input="set(r.key, { price: inputValue($event, numInput) })" />
      </label>
      <label class="bk-cell"><span class="t-label-s muted">{{ tr('Discount', 'ছাড়') }}</span>
        <input class="input num" inputmode="decimal" :value="r.disc" placeholder="০" @input="set(r.key, { disc: inputValue($event, numInput) })" />
      </label>
      <span class="bk-line-total num semi">{{ total(r) }}</span>
      <IconButton small icon="close" :label="tr('Remove', 'সরান') + ': ' + (r.name || '—')" @click="rows = rows.filter((x) => x.key !== r.key)" />
    </div>
  </div>
</template>
