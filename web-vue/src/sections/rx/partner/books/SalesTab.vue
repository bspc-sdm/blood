<script setup lang="ts">
// ফার্মেসি — "বিক্রি": কাউন্টারে তাড়াতাড়ি বিক্রি লেখা (পণ্য খুঁজে কয়টা; দাম আর ছাড় নিজে থেকে, বদলানো যায়; পুরো বিলে % বা টাকায় ছাড়),
// পাশে আজকের/এই সপ্তাহের বিক্রি (কাউন্টার, প্রেসক্রিপশন, অনলাইন)।
import { computed, ref } from 'vue';
import { Button, ConfirmDialog, ErrorText, IconButton, Loading, Segmented, Tag, inputValue, toast } from '@/ui/kit';
import { call, errorText } from '@/lib/api';
import { useCall } from '@/lib/query';
import { num, tr } from '@/lib/i18n';
import { formatDayOf, formatTime } from '@/lib/format';
import Panel from '../Panel.vue';
import ItemPicker from './ItemPicker.vue';
import { money, numInput, numStr, qtyText, r2, readNum, sourceText, unitDisc, unitText, useBooksRefresh, type Sale, type ShopItem } from './model';

interface Line { item: ShopItem; qty: string; price: string; disc: string }

/** বিলের হিসাব (সার্ভারের priceSale-এর মতো — দেখানোর জন্য; আসল হিসাব সার্ভারে) */
function billOf(lines: Line[], billType: 'pct' | 'amt', billV: number | null) {
  let gross = 0;
  let after = 0;
  let lineDisc = 0;
  for (const l of lines) {
    const q = readNum(l.qty) || 0;
    const p = readNum(l.price);
    if (p == null || Number.isNaN(p)) continue;
    const g = q * p;
    const d = Math.min(g, q * (readNum(l.disc) || 0));
    gross += g;
    lineDisc += d;
    after += g - d;
  }
  const bd = billV && after > 0 ? Math.min(after, billType === 'pct' ? (after * billV) / 100 : billV) : 0;
  return { gross: r2(gross), discount: r2(lineDisc + bd), net: r2(after - bd) };
}

const items = useCall<{ items: ShopItem[] }>('books_items', {});
const period = ref<'today' | '7d'>('today');
const sales = useCall<{ items: Sale[] }>('books_sales', () => ({ period: period.value }));
const refresh = useBooksRefresh();
const lines = ref<Line[]>([]);
const billType = ref<'pct' | 'amt'>('pct');
const billV = ref('');
const busy = ref(false);
const err = ref<string | null>(null);
const deleting = ref<Sale | null>(null);

function add(i: ShopItem) {
  const ex = lines.value.find((l) => l.item.id === i.id);
  if (ex) {
    lines.value = lines.value.map((l) => (l === ex ? { ...l, qty: String((readNum(l.qty) || 0) + 1) } : l));
    return;
  }
  lines.value = [...lines.value, { item: i, qty: '1', price: numStr(i.price), disc: i.price != null && i.disc ? String(unitDisc(i.price, i.disc)) : '' }];
}
const set = (id: string, p: Partial<Line>) => (lines.value = lines.value.map((l) => (l.item.id === id ? { ...l, ...p } : l)));
const bv = computed(() => readNum(billV.value));
const bad = computed(() => Number.isNaN(bv.value) || lines.value.some((l) => [l.qty, l.price, l.disc].some((s) => Number.isNaN(readNum(s)))));
const ready = computed(() => lines.value.filter((l) => (readNum(l.qty) || 0) > 0));
const bill = computed(() => billOf(ready.value, billType.value, bv.value));

async function save() {
  if (!ready.value.length || bad.value) return;
  busy.value = true;
  err.value = null;
  try {
    const r = await call<{ sale: Sale }>('books_sale_add', {
      lines: ready.value.map((l) => ({ itemId: l.item.id, qty: readNum(l.qty), unitPrice: readNum(l.price), unitDisc: readNum(l.disc) || 0 })),
      billDisc: bv.value ? { type: billType.value, v: bv.value } : null,
    });
    toast(tr('Sale saved', 'বিক্রি লেখা হয়েছে') + ' · ' + money(r.sale.totals.net));
    lines.value = [];
    billV.value = '';
    refresh();
  } catch (e) {
    err.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
const list = computed(() => sales.data?.items ?? []);
const sum = computed(() => r2(list.value.reduce((a, s) => a + s.totals.net, 0)));
const lineInfo = (l: Line) => {
  const q = readNum(l.qty) || 0;
  const p = readNum(l.price);
  const d = readNum(l.disc) || 0;
  const low = l.item.cost != null && p != null && !Number.isNaN(p) && p - d < l.item.cost;
  const total = p != null && !Number.isNaN(p) ? money(r2(q * (p - Math.min(p, d)))) : '—';
  return { low, total };
};
function clear() {
  lines.value = [];
  billV.value = '';
}
async function del() {
  try {
    await call('books_sale_delete', { id: deleting.value!.id });
    refresh();
  } catch (e) {
    toast(errorText(e));
  }
  deleting.value = null;
}
</script>

<template>
  <div class="rxp-grid">
    <div class="col gap-16 rxp-main">
      <Panel :title="tr('New sale', 'নতুন বিক্রি')" icon="payments">
        <Loading v-if="!items.data" />
        <ItemPicker v-else :items="items.data.items" @pick="add" />
        <div v-if="lines.length" class="col gap-8">
          <div v-for="l in lines" :key="l.item.id" class="bk-line">
            <div class="col gap-2 bk-line-name">
              <span class="semi">{{ l.item.name }}</span>
              <span class="t-body-s muted num">{{ l.item.qty != null ? tr('Stock ', 'স্টক ') + qtyText(l.item.qty) + (l.item.unit ? ` ${unitText(l.item.unit)}` : '') : '' }}<span v-if="lineInfo(l).low" class="c-error semi"> · {{ tr('below cost', 'কেনা দামের নিচে') }}</span></span>
            </div>
            <label class="bk-cell"><span class="t-label-s muted">{{ tr('Qty', 'কয়টা') }}</span>
              <input class="input num" inputmode="decimal" :value="l.qty" @input="set(l.item.id, { qty: inputValue($event, numInput) })" />
            </label>
            <label class="bk-cell"><span class="t-label-s muted">{{ tr('Price', 'দাম') }}</span>
              <input class="input num" inputmode="decimal" :value="l.price" placeholder="—" @input="set(l.item.id, { price: inputValue($event, numInput) })" />
            </label>
            <label class="bk-cell"><span class="t-label-s muted">{{ tr('Disc / unit', 'ছাড় / একক') }}</span>
              <input class="input num" inputmode="decimal" :value="l.disc" placeholder="০" @input="set(l.item.id, { disc: inputValue($event, numInput) })" />
            </label>
            <span class="bk-line-total num semi">{{ lineInfo(l).total }}</span>
            <IconButton small icon="close" :label="tr('Remove', 'সরান') + ': ' + l.item.name" @click="lines = lines.filter((x) => x !== l)" />
          </div>
        </div>
        <div v-if="lines.length" class="row wrap gap-8">
          <span class="t-label-l">{{ tr('Bill discount', 'বিলে ছাড়') }}</span>
          <Segmented v-model="billType" :label="tr('Bill discount', 'বিলে ছাড়')" :options="[{ value: 'pct', label: '%' }, { value: 'amt', label: tr('Tk', 'টাকা') }]" />
          <input class="input num bk-small-input" inputmode="decimal" :value="billV" placeholder="০" :aria-label="tr('Bill discount', 'বিলে ছাড়')" @input="billV = inputValue($event, numInput)" />
        </div>
        <div class="bk-bill">
          <dl class="kv num">
            <dt>{{ tr('Total', 'মোট') }}</dt><dd>{{ money(bill.gross) }}</dd>
            <dt>{{ tr('Discount', 'ছাড়') }}</dt><dd>{{ money(bill.discount) }}</dd>
          </dl>
          <div class="col gap-2 bk-pay">
            <span class="t-label-m muted">{{ tr('To pay', 'দিতে হবে') }}</span>
            <span class="bk-big num">{{ money(bill.net) }}</span>
          </div>
        </div>
        <div class="row wrap gap-8">
          <Button icon="check_circle" :busy="busy" :disabled="!ready.length || bad" @click="save">{{ tr('Save sale', 'বিক্রি লিখুন') }}</Button>
          <Button v-if="lines.length" variant="text" @click="clear">{{ tr('Clear', 'মুছে নতুন') }}</Button>
        </div>
        <ErrorText :text="err" />
      </Panel>
    </div>
    <aside class="col gap-16 rxp-side">
      <Panel :title="tr('Sales', 'বিক্রি')" icon="history" :count="sales.data ? money(sum) : undefined">
        <div class="row"><Segmented v-model="period" :options="[{ value: 'today', label: tr('Today', 'আজ') }, { value: '7d', label: tr('7 days', '৭ দিন') }]" /></div>
        <template v-if="!sales.data">
          <ErrorText v-if="sales.error" :text="errorText(sales.error)" />
          <Loading v-else />
        </template>
        <p v-else-if="!list.length" class="t-body-m muted">{{ tr('No sales yet', 'এখনো বিক্রি নেই') }}</p>
        <ul v-else class="bk-list">
          <li v-for="s in list.slice(0, 80)" :key="s.id">
            <div class="row">
              <Tag :tone="s.source === 'counter' ? undefined : s.source === 'rx' ? 'primary' : 'info'">{{ sourceText(s.source) }}</Tag>
              <span class="t-body-s muted grow">{{ period === 'today' ? formatTime(s.at) : formatDayOf(s.at) }}{{ s.rx?.code ? ` · ${s.rx.code}` : s.customer ? ` · ${s.customer}` : '' }}</span>
              <span class="num semi">{{ money(s.totals.net) }}</span>
              <IconButton small icon="delete" :label="tr('Delete', 'মুছুন')" @click="deleting = s" />
            </div>
            <span class="t-body-s muted">{{ s.lines.map((l) => `${l.name} ×${qtyText(l.qty)}`).join(', ') }}</span>
            <span v-if="s.totals.profit < 0" class="t-body-s c-error">{{ tr('Loss ', 'ক্ষতি ') }}{{ money(-s.totals.profit) }}</span>
          </li>
        </ul>
        <span v-if="sales.data && list.length" class="t-body-s muted">{{ tr(`${list.length} sales`, `${num(list.length)}টা বিক্রি`) }}</span>
      </Panel>
    </aside>
    <ConfirmDialog
      v-if="deleting"
      :title="tr('Delete this sale?', 'এই বিক্রি মুছবেন?')"
      :text="tr('The quantities go back to stock.', 'পরিমাণগুলো স্টকে ফিরে যাবে।')"
      :confirm="tr('Delete', 'মুছুন')"
      danger
      @close="deleting = null"
      @confirm="del"
    />
  </div>
</template>
