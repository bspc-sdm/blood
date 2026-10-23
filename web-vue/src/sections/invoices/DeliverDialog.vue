<script setup lang="ts">
// অনলাইন অর্ডার পৌঁছে দেওয়া (rx_order_update status "delivered" + lines) — কী কী দিলেন, দাম, ডেলিভারি চার্জ, পরিশোধ।
// সার্ভার রসিদ তৈরি করে রোগীকে পাঠায় (invoice_new); এখানে রসিদ দেখা/ছাপা। দোকানের পণ্যের তালিকা থেকে খুঁজে যোগ করা যায়।
import { computed, ref } from 'vue';
import { Button, Dialog, ErrorText, Icon, Loading, inputValue, toast } from '@/ui/kit';
import { call, errorText } from '@/lib/api';
import { useCall } from '@/lib/query';
import { tr } from '@/lib/i18n';
import { numInput, readNum, unitDisc, type ShopItem } from '@/sections/rx/partner/books/model';
import ItemPicker from '@/sections/rx/partner/books/ItemPicker.vue';
import { useInvalidate } from '@/sections/rx/partner/common';
import type { PharmOrder } from '@/sections/rx/partner/model';
import { T } from './text';
import LinesEditor, { linesOf, rowKey, type Row } from './LinesEditor.vue';
import PayFields from './PayFields.vue';
import ReceiptView from './ReceiptView.vue';
import { billOf, money, paymentsOf, pickInvoice, type Invoice, type PayMethod } from './model';
import { useInvoiceRefresh } from './actions';

const props = defineProps<{ order: PharmOrder }>();
const emit = defineEmits<{ close: [] }>();
const invalidate = useInvalidate();
const refreshInv = useInvoiceRefresh();
const items = useCall<{ items: ShopItem[] }>('books_items', {});
const rows = ref<Row[]>([]);
// ডেলিভারি চার্জ: দোকানের লেখা থেকে প্রথম সংখ্যা (যেমন "৩০ টাকা" → ৩০)
const feeGuess = readNum((props.order.deliveryFee || '').replace(/[^0-9০-৯.]/g, ' ').trim().split(/\s+/)[0] || '');
const fee = ref(feeGuess != null && !Number.isNaN(feeGuess) ? String(feeGuess) : '');
const method = ref<PayMethod>('cash');
const given = ref('');
const txRef = ref('');
const busy = ref(false);
const err = ref<string | null>(null);
const done = ref<Invoice | null>(null);

function pick(i: ShopItem) {
  const ex = rows.value.find((r) => r.itemId === i.id);
  if (ex) {
    rows.value = rows.value.map((r) => (r === ex ? { ...r, qty: String((readNum(r.qty) || 0) + 1) } : r));
    return;
  }
  rows.value = [...rows.value, { key: rowKey(), name: i.name, qty: '1', price: i.price != null ? String(i.price - unitDisc(i.price, i.disc)) : '', disc: '', itemId: i.id, code: i.code ?? null, fixed: true }];
}
const addFree = () => (rows.value = [...rows.value, { key: rowKey(), name: '', qty: '1', price: '', disc: '' }]);
const lines = computed(() => linesOf(rows.value));
const feeV = computed(() => readNum(fee.value) || 0);
const fees = computed(() => (feeV.value > 0 ? [{ label: T.deliveryFee(), amount: feeV.value }] : []));
const bill = computed(() => billOf(lines.value, null, fees.value));
async function save() {
  if (!lines.value.length) return;
  busy.value = true;
  err.value = null;
  try {
    const g = readNum(given.value);
    const r = await call('rx_order_update', {
      id: props.order.id,
      status: 'delivered',
      lines: lines.value,
      fees: fees.value,
      payments: paymentsOf(bill.value.total, method.value, g == null || Number.isNaN(g) ? null : g, txRef.value),
    });
    toast(tr('The patient was told', 'রোগীকে জানানো হয়েছে'));
    await invalidate('rx_orders_pharmacy', 'rx_pharmacy_home');
    refreshInv();
    const inv = pickInvoice(r);
    if (inv) done.value = inv;
    else emit('close');
  } catch (e) {
    err.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <Dialog size="wide" :title="done ? T.receipt() : T.deliverTitle()" @close="() => !busy && emit('close')">
    <ReceiptView v-if="done" :inv="done" />
    <template v-else>
      <div class="col gap-4">
        <span class="t-title-m">{{ order.patientName || '—' }}</span>
        <span v-if="order.address" class="icon-line"><Icon name="location_on" /><span>{{ order.address }}</span></span>
        <span v-if="order.price" class="icon-line"><Icon name="list_alt" /><span>{{ order.price }}</span></span>
      </div>
      <span class="t-body-s muted">{{ T.deliverNote() }}</span>
      <Loading v-if="!items.data && items.isLoading" />
      <ItemPicker v-else :items="items.data?.items ?? []" @pick="pick" />
      <LinesEditor v-model="rows" />
      <div><Button small variant="text" icon="add" @click="addFree">{{ T.addLine() }}</Button></div>
      <label class="bk-cell inv-fee">
        <span class="t-label-s muted">{{ T.deliveryFee() }}</span>
        <input class="input num" inputmode="decimal" :value="fee" placeholder="০" @input="fee = inputValue($event, numInput)" />
      </label>
      <div class="bk-bill">
        <dl class="kv num">
          <dt>{{ T.subtotal() }}</dt><dd>{{ money(bill.subtotal) }}</dd>
          <dt>{{ tr('Discount', 'ছাড়') }}</dt><dd>{{ money(bill.disc) }}</dd>
          <template v-if="bill.fees"><dt>{{ T.deliveryFee() }}</dt><dd>{{ money(bill.fees) }}</dd></template>
        </dl>
        <div class="col gap-2 bk-pay">
          <span class="t-label-m muted">{{ tr('To pay', 'দিতে হবে') }}</span>
          <span class="bk-big num" data-testid="deliver-total">{{ money(bill.total) }}</span>
        </div>
      </div>
      <PayFields v-model:method="method" v-model:given="given" v-model:tx-ref="txRef" :total="bill.total" />
      <ErrorText :text="err" />
    </template>
    <template #actions>
      <template v-if="done"><Button @click="emit('close')">{{ tr('Done', 'হয়ে গেছে') }}</Button></template>
      <template v-else>
        <Button variant="text" :disabled="busy" @click="emit('close')">{{ tr('Cancel', 'বাতিল') }}</Button>
        <Button icon="check_circle" :busy="busy" :disabled="!lines.length" data-testid="deliver-save" @click="save">{{ T.delivered() }}</Button>
      </template>
    </template>
  </Dialog>
</template>
