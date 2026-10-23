<script setup lang="ts">
// প্যাথলজির বিল (lab_bill_create): অনুরোধ থেকে (?order=<id> — পরীক্ষা আর দাম পাঠানোর সময়ের তালিকা থেকে আগে বসানো)
// বা সরাসরি আসা রোগীর পরীক্ষা (দামের তালিকা থেকে খুঁজে)। চার্জ (বাসা থেকে নমুনা, অন্য), বিলে ছাড় (% বা টাকা), পরিশোধ, ফেরত/বাকি।
// বিলের পর রসিদ (চাইলে সাথে সাথে প্রিন্ট)। ডেস্কটপে দামের তালিকা স্থানীয়, বিল অফলাইনে লেখা হয়ে পরে মেলে।
import { computed, ref, shallowRef, watch } from 'vue';
import { Button, Check, ErrorText, Icon, Loading, Segmented, inputValue, toast } from '@/ui/kit';
import { errorText } from '@/lib/api';
import { useCall } from '@/lib/query';
import { tr } from '@/lib/i18n';
import { useSession } from '@/lib/session';
import { useNav, useSearchParams } from '@/lib/nav';
import { store as kv } from '@/lib/store';
import { numInput, readNum } from '@/sections/rx/partner/books/model';
import { norm } from '@/sections/rx/partner/labprices';
import Panel from '@/sections/rx/partner/Panel.vue';
import type { LabOrder, LabPriceItem, LabPriceList } from '@/sections/rx/partner/model';
import { T } from './text';
import LinesEditor, { linesOf, rowKey, type Row } from './LinesEditor.vue';
import PayFields from './PayFields.vue';
import OfflineBar from './OfflineBar.vue';
import ReceiptDialog from './ReceiptDialog.vue';
import { billOf, money, paymentsOf, r2, type InvFee, type Invoice, type PayMethod } from './model';
import { errText, issue, useInvoiceRefresh } from './actions';
import { ownerGate, useOffline } from './offline';
import type { LabBillData } from '@/offline';

const s = useSession();
const nav = useNav();
const [sp] = useSearchParams();
const refresh = useInvoiceRefresh();
const bizId = computed(() => (s.work.lab as { id?: string } | null)?.id ?? '');
const off = useOffline('lab', bizId);
const orderId = computed(() => sp.value.get('order'));
const online = useCall<LabPriceList>('lab_prices_get', {}, () => ({ enabled: !off.st.desktop, staleTime: 60_000 }));
const local = shallowRef<LabPriceList | null>(null);
watch(
  () => off.st.ready,
  async (r) => {
    const st = off.store();
    if (!r || !st) return;
    // ডেস্কটপের দামের তালিকা ({items, fees}) → নিজের তালিকার আকার; "বাসা"/home নামের চার্জ = বাসা থেকে নমুনা
    const lp = await st.labPrices();
    const home = lp.fees.find((f) => /home|বাসা/i.test(f.label)); // bn-ok
    local.value = { items: lp.items.map((i) => ({ code: i.code ?? null, name: i.name, price: i.price, tat: '', on: true })), discountPct: null, homeFee: home?.amount ?? null, updatedAt: 0, homeCollection: !!home };
  },
  { immediate: true },
);
const prices = computed<LabPriceList | null>(() => (off.st.desktop ? local.value : online.data ?? null));
const order = useCall<{ item: LabOrder }>('lab_order_get', () => ({ id: orderId.value }), () => ({ enabled: !!orderId.value && !off.st.desktop }));
const o = computed(() => order.data?.item ?? null);

const rows = ref<Row[]>([]);
const custName = ref('');
const custPhone = ref('');
const home = ref(false);
const homeFee = ref('');
const otherLabel = ref('');
const otherFee = ref('');
const billType = ref<'pct' | 'amt'>('pct');
const billV = ref('');
const method = ref<PayMethod>('cash');
const given = ref('');
const txRef = ref('');
const busy = ref(false);
const err = ref<string | null>(null);
const PRINT_KEY = 'lf.labPrint';
const autoPrint = ref(kv.get(PRINT_KEY) !== '0');
watch(autoPrint, (v) => kv.set(PRINT_KEY, v ? '1' : '0'));
const receipt = ref<{ inv: Invoice; print: boolean } | null>(null);

// অনুরোধ থেকে: পরীক্ষা আর দাম আগে বসানো (দাম না থাকলে খালি — লিখে দিন), রোগীর নাম, বাসা থেকে নমুনার চার্জ
watch(
  o,
  (x) => {
    if (!x) return;
    const snap = x.prices?.items ?? [];
    const named = new Set(snap.map((i) => i.name));
    rows.value = [
      ...snap.map((i) => ({ key: rowKey(), name: i.name, qty: '1', price: String(i.price), disc: '', code: i.test || null, fixed: true })),
      ...x.tests.filter((t) => !named.has(t) && !snap.some((i) => i.test === t)).map((t) => ({ key: rowKey(), name: t, qty: '1', price: '', disc: '', fixed: true })),
    ];
    custName.value = x.patientName || '';
    if (x.home && x.home.status !== 'declined') {
      home.value = true;
      homeFee.value = x.home.fee != null ? String(x.home.fee) : '';
    }
    if (x.prices?.discountPct) {
      billType.value = 'pct';
      billV.value = String(x.prices.discountPct);
    }
  },
  { immediate: true },
);
watch(prices, (p) => {
  if (p && !homeFee.value && p.homeFee != null) homeFee.value = String(p.homeFee);
}, { immediate: true });

// পরীক্ষা খোঁজা (দামের তালিকা — চালু থাকা)
const q = ref('');
const found = computed(() => {
  const t = norm(q.value);
  if (!t) return [] as LabPriceItem[];
  const list = (prices.value?.items ?? []).filter((i) => i.on !== false && !i.hidden);
  return list.filter((i) => norm(i.name).includes(t) || (i.code ?? '').toLowerCase() === t).slice(0, 8);
});
function addTest(i: LabPriceItem) {
  if (!rows.value.some((r) => r.name === i.name)) rows.value = [...rows.value, { key: rowKey(), name: i.name, qty: '1', price: String(i.price), disc: '', code: i.code, fixed: true }];
  q.value = '';
}
const addFree = () => (rows.value = [...rows.value, { key: rowKey(), name: '', qty: '1', price: '', disc: '' }]);

const lines = computed(() => linesOf(rows.value));
const fees = computed<InvFee[]>(() => {
  const out: InvFee[] = [];
  const h = readNum(homeFee.value);
  if (home.value && h) out.push({ label: T.homeCollectionFee(), amount: h });
  const x = readNum(otherFee.value);
  if (x) out.push({ label: otherLabel.value.trim() || T.otherFee(), amount: x });
  return out;
});
const bv = computed(() => readNum(billV.value));
const bill = computed(() => billOf(lines.value, bv.value ? { type: billType.value, v: bv.value } : null, fees.value));
const lineDisc = computed(() => r2(lines.value.reduce((a, l) => a + l.disc, 0)));
const missingPrice = computed(() => rows.value.some((r) => r.name.trim() && readNum(r.price) == null));

function clear() {
  rows.value = [];
  custName.value = '';
  custPhone.value = '';
  home.value = false;
  otherLabel.value = '';
  otherFee.value = '';
  billV.value = '';
  given.value = '';
  txRef.value = '';
  method.value = 'cash';
}
async function save() {
  if (!lines.value.length || missingPrice.value || busy.value) return;
  busy.value = true;
  err.value = null;
  try {
    const g = readNum(given.value);
    const customer = { ...(custName.value.trim() ? { name: custName.value.trim() } : {}), ...(custPhone.value.trim() ? { phone: custPhone.value.trim() } : {}) };
    // ১০%-এর বেশি ছাড়: ডেস্কটপে কাউন্টারের পিন হলে মালিকের পিন
    if (bill.value.subtotal > 0 && bill.value.disc / bill.value.subtotal > 0.1) await ownerGate();
    const data: LabBillData = {
      ...(orderId.value ? { labOrderId: orderId.value } : {}),
      lines: lines.value,
      ...(Object.keys(customer).length ? { customer } : {}),
      fees: fees.value,
      // বিলের ছাড় (টাকায়) — লাইনের ছাড় লাইনেই
      disc: r2(bill.value.disc - lineDisc.value),
      payments: paymentsOf(bill.value.total, method.value, g == null || Number.isNaN(g) ? null : g, txRef.value),
    };
    const r = await issue('lab_bill_create', { ...data }, () => ({ type: 'lab_bill', data }));
    toast(T.billSaved() + ' · ' + money(r.invoice?.total ?? bill.value.total));
    clear();
    refresh();
    if (r.invoice) receipt.value = { inv: r.invoice, print: autoPrint.value };
    if (orderId.value) nav('/work/lab/orders/bill', { replace: true });
  } catch (e) {
    err.value = errText(e);
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <div class="col gap-16 inv-labbill">
    <OfflineBar :st="off.st" :sync="off.syncNow" />
    <Panel :title="orderId ? T.billFromOrder() : T.newBill()" icon="edit_note">
      <span v-if="o" class="t-body-m muted">{{ T.fromOrder(o.patientName || '—') }}</span>
      <Loading v-if="orderId && order.isLoading" />
      <ErrorText v-else-if="order.error" :text="errorText(order.error)" />
      <div class="col gap-6">
        <div class="rxp-search">
          <Icon name="search" />
          <input class="input" :value="q" maxlength="60" role="combobox" :aria-expanded="found.length > 0" :aria-label="T.pickTest()" :placeholder="T.pickTest()" data-testid="lab-test-search" @input="q = ($event.target as HTMLInputElement).value" @keydown.enter.prevent="found[0] && addTest(found[0])" />
        </div>
        <ul v-if="q.trim()" class="rxp-found" role="listbox">
          <li v-for="i in found" :key="i.name" role="option" :aria-selected="false" class="bk-pick" @click="addTest(i)">
            <span class="grow semi">{{ i.name }}</span><span class="num">{{ money(i.price) }}</span><Icon name="add" />
          </li>
          <li v-if="!found.length" class="t-body-s muted">{{ tr('No match', 'মেলেনি') }}</li>
        </ul>
      </div>
      <LinesEditor v-model="rows" no-qty />
      <div><Button small variant="text" icon="add" @click="addFree">{{ T.addLine() }}</Button></div>
      <div class="row wrap gap-8 inv-cust">
        <label class="bk-cell grow"><span class="t-label-s muted">{{ T.customerName() }}</span>
          <input class="input" :value="custName" maxlength="60" @input="custName = ($event.target as HTMLInputElement).value" />
        </label>
        <label class="bk-cell"><span class="t-label-s muted">{{ T.customerPhone() }}</span>
          <input class="input num" inputmode="tel" :value="custPhone" maxlength="14" @input="custPhone = inputValue($event, (v) => v.replace(/[^0-9+০-৯]/g, ''))" />
        </label>
      </div>
      <div class="row wrap gap-8">
        <Check v-model="home">{{ T.homeCollectionFee() }}</Check>
        <input v-if="home" class="input num bk-small-input" inputmode="decimal" :value="homeFee" placeholder="০" :aria-label="T.homeCollectionFee()" data-testid="lab-home-fee" @input="homeFee = inputValue($event, numInput)" />
      </div>
      <div class="row wrap gap-8">
        <input class="input grow" :value="otherLabel" maxlength="40" :placeholder="T.otherFee()" :aria-label="T.otherFee()" @input="otherLabel = ($event.target as HTMLInputElement).value" />
        <input class="input num bk-small-input" inputmode="decimal" :value="otherFee" placeholder="০" :aria-label="T.amount()" @input="otherFee = inputValue($event, numInput)" />
      </div>
      <div class="row wrap gap-8">
        <span class="t-label-l">{{ tr('Bill discount', 'বিলে ছাড়') }}</span>
        <Segmented v-model="billType" :label="tr('Bill discount', 'বিলে ছাড়')" :options="[{ value: 'pct', label: '%' }, { value: 'amt', label: tr('Tk', 'টাকা') }]" />
        <input class="input num bk-small-input" inputmode="decimal" :value="billV" placeholder="০" :aria-label="tr('Bill discount', 'বিলে ছাড়')" @input="billV = inputValue($event, numInput)" />
      </div>
      <div class="bk-bill">
        <dl class="kv num">
          <dt>{{ T.subtotal() }}</dt><dd>{{ money(bill.subtotal) }}</dd>
          <dt>{{ tr('Discount', 'ছাড়') }}</dt><dd>{{ money(bill.disc) }}</dd>
          <template v-for="f in fees" :key="f.label"><dt>{{ f.label }}</dt><dd>{{ money(f.amount) }}</dd></template>
        </dl>
        <div class="col gap-2 bk-pay">
          <span class="t-label-m muted">{{ tr('To pay', 'দিতে হবে') }}</span>
          <span class="bk-big num" data-testid="lab-total">{{ money(bill.total) }}</span>
        </div>
      </div>
      <PayFields v-model:method="method" v-model:given="given" v-model:tx-ref="txRef" :total="bill.total" />
      <div class="row wrap gap-8">
        <Button icon="check_circle" :busy="busy" :disabled="!lines.length || missingPrice" data-testid="lab-save" @click="save">{{ T.billFromOrder() }}</Button>
        <Button v-if="rows.length" variant="text" @click="clear">{{ tr('Clear', 'মুছে নতুন') }}</Button>
        <span class="grow" />
        <Check v-model="autoPrint">{{ T.printAfterBill() }}</Check>
      </div>
      <ErrorText :text="err" />
    </Panel>
    <ReceiptDialog v-if="receipt" :inv="receipt.inv" :auto-print="receipt.print" @close="receipt = null" />
  </div>
</template>
