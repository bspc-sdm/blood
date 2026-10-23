<script setup lang="ts">
// ফার্মেসি — "বিক্রি": কাউন্টারে তাড়াতাড়ি বিক্রি লেখা (POS): পণ্য খুঁজে বা বারকোড স্ক্যান করে (Enter-এ যোগ), কয়টা; দাম আর ছাড়
// নিজে থেকে, বদলানো যায়; পুরো বিলে % বা টাকায় ছাড়; ক্রেতা (ঐচ্ছিক), পরিশোধের মাধ্যম, কত পেলেন → ফেরত/বাকি;
// "বিক্রি লিখুন" (Ctrl+Enter) — রসিদ তৈরি (books_sale_add-এর invoice), চাইলে সাথে সাথে প্রিন্ট।
// ডেস্কটপ অ্যাপে (OfflineStore): পণ্য স্থানীয় তালিকা থেকে, বিক্রি স্থানীয়ভাবে লেখা হয়ে পরে মেলে, ছাপা অ্যাপের প্রিন্টারে।
// পাশে আজকের/এই সপ্তাহের বিক্রি (কাউন্টার, প্রেসক্রিপশন, অনলাইন)।
import { computed, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue';
import { Button, Check, ConfirmDialog, ErrorText, IconButton, Loading, Segmented, Tag, inputValue, toast } from '@/ui/kit';
import { call, errorText } from '@/lib/api';
import { useCall } from '@/lib/query';
import { num, tr } from '@/lib/i18n';
import { formatDayOf, formatTime } from '@/lib/format';
import { useSession } from '@/lib/session';
import { store as kv } from '@/lib/store';
import Panel from '../Panel.vue';
import ItemPicker from './ItemPicker.vue';
import { money, numInput, numStr, qtyText, r2, readNum, sourceText, unitDisc, unitText, useBooksRefresh, type Sale, type ShopItem } from './model';
import { T } from '../../../invoices/text';
import { paymentsOf, pickInvoice, type Invoice, type PayMethod } from '../../../invoices/model';
import { errText, issue } from '../../../invoices/actions';
import { ownerGate, useOffline } from '../../../invoices/offline';
import type { OfflineItem, SaleData } from '@/offline';
import PayFields from '../../../invoices/PayFields.vue';
import OfflineBar from '../../../invoices/OfflineBar.vue';
import ReceiptDialog from '../../../invoices/ReceiptDialog.vue';
import { invFeature } from '../../../invoices/features';

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

const s = useSession();
const bizId = computed(() => (s.work.pharmacy as { id?: string } | null)?.id ?? '');
// ফিচার সুইচ inv_pharmacy বন্ধ: আগের মতোই (রসিদ, ক্রেতা, পরিশোধ, ডেস্কটপের অফলাইন বিক্রি — কিছুই নয়)
const inv = computed(() => invFeature('inv_pharmacy'));
const off = useOffline('pharmacy', () => (inv.value ? bizId.value : null));
const online = useCall<{ items: ShopItem[] }>('books_items', {}, () => ({ enabled: !(off.st.desktop && inv.value) }));
// ডেস্কটপে পণ্যের তালিকা স্থানীয় (অফলাইনেও চলে)
const local = shallowRef<ShopItem[] | null>(null);
/** ডেস্কটপের জিনিস (stock = সার্ভারের + এখনো না মেলা বদল) → হিসাব-খাতার আকার */
const fromOffline = (i: OfflineItem): ShopItem => ({
  id: i.id, name: i.name, generic: i.generic ?? '', form: i.form ?? '', unit: i.unit ?? '', qty: i.stock, cost: i.cost ?? null, price: i.price, disc: null,
  salePrice: i.price, lowAt: null, expiry: i.expiry ?? null, inStock: i.stock > 0, low: false, expDays: null, lastSaleAt: null, updatedAt: 0, code: i.code ?? null,
});
async function loadLocal() {
  const st = off.store();
  if (st) local.value = (await st.items('')).map(fromOffline);
}
watch(() => off.st.ready, (r) => r && loadLocal(), { immediate: true });
const items = computed(() => (off.st.desktop && inv.value ? (local.value ? { items: local.value } : null) : online.data ?? null));
const period = ref<'today' | '7d'>('today');
const sales = useCall<{ items: Sale[] }>('books_sales', () => ({ period: period.value }));
const refresh = useBooksRefresh();
const lines = ref<Line[]>([]);
const billType = ref<'pct' | 'amt'>('pct');
const billV = ref('');
const busy = ref(false);
const err = ref<string | null>(null);
const deleting = ref<Sale | null>(null);
const custName = ref('');
const custPhone = ref('');
const method = ref<PayMethod>('cash');
const given = ref('');
const txRef = ref('');
const PRINT_KEY = 'lf.posPrint';
const autoPrint = ref(kv.get(PRINT_KEY) !== '0');
watch(autoPrint, (v) => kv.set(PRINT_KEY, v ? '1' : '0'));
const receipt = ref<{ inv: Invoice; print: boolean } | null>(null);

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
  if (!ready.value.length || bad.value || busy.value) return;
  busy.value = true;
  err.value = null;
  try {
    if (!inv.value) {
      // আগের মতো (রসিদ ছাড়া)
      const r0 = await call<{ sale: Sale }>('books_sale_add', {
        lines: ready.value.map((l) => ({ itemId: l.item.id, qty: readNum(l.qty), unitPrice: readNum(l.price), unitDisc: readNum(l.disc) || 0 })),
        billDisc: bv.value ? { type: billType.value, v: bv.value } : null,
      });
      toast(tr('Sale saved', 'বিক্রি লেখা হয়েছে') + ' · ' + money(r0.sale.totals.net));
      clear();
      refresh();
      return;
    }
    const g = readNum(given.value);
    const customer = { ...(custName.value.trim() ? { name: custName.value.trim() } : {}), ...(custPhone.value.trim() ? { phone: custPhone.value.trim() } : {}) };
    const payments = paymentsOf(bill.value.net, method.value, g == null || Number.isNaN(g) ? null : g, txRef.value);
    // জিনিসের দামের চেয়ে কম দাম বা ১০%-এর বেশি ছাড়: ডেস্কটপে কাউন্টারের পিন হলে মালিকের পিন (নিরাপত্তা পর্যালোচনা I7)
    const cheaper = ready.value.some((l) => l.item.price != null && (readNum(l.price) ?? l.item.price) < l.item.price);
    if (cheaper || (bill.value.gross > 0 && bill.value.discount / bill.value.gross > 0.1)) await ownerGate();
    const lineDisc = r2(ready.value.reduce((a, l) => a + (readNum(l.qty) || 0) * (readNum(l.disc) || 0), 0));
    const offData = (): SaleData => ({
      lines: ready.value.map((l) => ({ itemId: l.item.id, name: l.item.name, qty: readNum(l.qty) || 0, price: readNum(l.price) ?? 0, disc: r2((readNum(l.qty) || 0) * (readNum(l.disc) || 0)), ...(l.item.code ? { code: l.item.code } : {}) })),
      ...(Object.keys(customer).length ? { customer } : {}),
      disc: r2(Math.max(0, bill.value.discount - lineDisc)),
      payments,
    });
    const r = await issue(
      'books_sale_add',
      {
        lines: ready.value.map((l) => ({ itemId: l.item.id, qty: readNum(l.qty), unitPrice: readNum(l.price), unitDisc: readNum(l.disc) || 0 })),
        billDisc: bv.value ? { type: billType.value, v: bv.value } : null,
        ...(Object.keys(customer).length ? { customer } : {}),
        payments,
      },
      () => ({ type: 'sale', data: offData() }),
    );
    const net = (r.raw as { sale?: Sale })?.sale?.totals.net ?? r.invoice?.total ?? bill.value.net;
    toast((r.invoice ? T.saleDone() : tr('Sale saved', 'বিক্রি লেখা হয়েছে')) + ' · ' + money(net));
    clear();
    if (r.invoice) receipt.value = { inv: r.invoice, print: autoPrint.value };
    refresh();
    if (r.offline) loadLocal();
  } catch (e) {
    err.value = errText(e);
  } finally {
    busy.value = false;
  }
}
// Ctrl+Enter (বা ⌘+Enter) — যেখানেই থাকুন, বিক্রি লেখা
function onKey(e: KeyboardEvent) {
  if (inv.value && e.key === 'Enter' && (e.ctrlKey || e.metaKey) && !receipt.value) {
    e.preventDefault();
    save();
  }
}
onMounted(() => window.addEventListener('keydown', onKey));
onBeforeUnmount(() => window.removeEventListener('keydown', onKey));
/** তালিকার বিক্রির রসিদ (সার্ভারে থাকলে) */
const invIdOf = (x: Sale) => (x as Sale & { invoiceId?: string; invoice?: { id?: string } }).invoiceId ?? (x as Sale & { invoice?: { id?: string } }).invoice?.id ?? null;
const opening = ref<string | null>(null);
async function openReceipt(x: Sale) {
  const id = invIdOf(x);
  if (!id) return;
  opening.value = x.id;
  try {
    const inv = pickInvoice(await call('invoice_get', { id }));
    if (inv) receipt.value = { inv, print: false };
  } catch (e) {
    toast(errorText(e));
  } finally {
    opening.value = null;
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
  custName.value = '';
  custPhone.value = '';
  given.value = '';
  txRef.value = '';
  method.value = 'cash';
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
      <OfflineBar v-if="inv" :st="off.st" :sync="off.syncNow" />
      <Panel :title="tr('New sale', 'নতুন বিক্রি')" icon="payments">
        <Loading v-if="!items" />
        <ItemPicker v-else :items="items.items" :placeholder="inv ? T.scanHint() : undefined" :autofocus="inv" @pick="add" />
        <span v-if="inv" class="t-body-s muted inv-hint">{{ T.shortcutHint() }}</span>
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
        <div v-if="inv && lines.length" class="row wrap gap-8 inv-cust">
          <label class="bk-cell grow"><span class="t-label-s muted">{{ T.customerName() }}</span>
            <input class="input" :value="custName" maxlength="60" data-testid="pos-cust-name" @input="custName = ($event.target as HTMLInputElement).value" />
          </label>
          <label class="bk-cell"><span class="t-label-s muted">{{ T.customerPhone() }}</span>
            <input class="input num" inputmode="tel" :value="custPhone" maxlength="14" @input="custPhone = inputValue($event, (v) => v.replace(/[^0-9+০-৯]/g, ''))" />
          </label>
        </div>
        <PayFields v-if="inv && lines.length" v-model:method="method" v-model:given="given" v-model:tx-ref="txRef" :total="bill.net" />
        <div class="row wrap gap-8">
          <Button icon="check_circle" :busy="busy" :disabled="!ready.length || bad" data-testid="pos-save" @click="save">{{ tr('Save sale', 'বিক্রি লিখুন') }}</Button>
          <Button v-if="lines.length" variant="text" @click="clear">{{ tr('Clear', 'মুছে নতুন') }}</Button>
          <span class="grow" />
          <Check v-if="inv" v-model="autoPrint">{{ T.printAfterSale() }}</Check>
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
              <Button v-if="inv && invIdOf(s)" small variant="text" :busy="opening === s.id" @click="openReceipt(s)">{{ T.seeReceipt() }}</Button>
              <IconButton v-else small icon="delete" :label="tr('Delete', 'মুছুন')" @click="deleting = s" />
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
    <ReceiptDialog v-if="receipt" :inv="receipt.inv" :auto-print="receipt.print" @close="receipt = null" />
  </div>
</template>
