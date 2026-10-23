<script setup lang="ts">
// ফার্মেসি — "অর্ডার" ট্যাব (আমার কাজ CONTRACT §২; আগে ২.২৭.০ "দুটোই সাজাও"):
// "কোড দিয়ে প্রেসক্রিপশন খুলুন" (কোড লিখে/পেস্ট করে সাথে সাথে খোলে) — ফোনে উপরে, ডেস্কটপে ডান ভাগে;
// স্থির ভাগ নতুন n · চলছে n · শেষ · ওষুধ খোঁজা n (খোঁজা /work/pharmacy/finds); দোকানে আসা আর অনলাইন অর্ডার একই তালিকায়,
// প্রতিটায় একটাই মূল বোতাম (OrderCard)। ডেস্কটপে (৯৬০px+) তালিকা-বিস্তারিত: বাঁয়ে ছোট সারি, ডানে কোডের বাক্স, বাছা অর্ডার
// আর খোলা প্রেসক্রিপশন। "রোগীরা আপনাকে যেভাবে দেখেন" তালিকার নিচে (কাজ আগে)।
// ভাগ সার্ভার ঠিক করে (rx_orders_pharmacy-এর seg, counts)। ?s=active|done — নইলে "নতুন"।
import { computed, ref } from 'vue';
import { Button, ErrorText, Icon, SkeletonCards, Tag } from '@/ui/kit';
import { errorText } from '@/lib/api';
import { num, tr } from '@/lib/i18n';
import { timeAgo } from '@/lib/format';
import { useNav, useSearchParams } from '@/lib/nav';
import { useTwoPane } from '@/shell/chrome';
import { PHARM_SEGS, orderStatusShop, orderTone, pharmSegOf, segCounts, type PharmOrder, type PharmOrders, type PharmSeg } from './model';
import { useOrderUpdate } from './online';
import CodeBox from './CodeBox.vue';
import BizPreview from '../../work/BizPreview.vue';
import PharmOrderSegs from './PharmOrderSegs.vue';
import RxPanel from './RxPanel.vue';
import OrderCard, { type OrderAct } from './OrderCard.vue';
import AcceptDialog from './AcceptDialog.vue';
import CancelOrderDialog from './CancelOrderDialog.vue';
import DeliverDialog from '../../invoices/DeliverDialog.vue';
import InvoiceByIdDialog from '../../invoices/InvoiceByIdDialog.vue';
import { useSession } from '@/lib/session';

const BASE = '/work/pharmacy/orders';
const props = defineProps<{ code: string | null; orders: { data?: PharmOrders; isLoading: boolean; isFetching: boolean; error: Error | null; refetch: () => unknown } }>();
const nav = useNav();
const [sp] = useSearchParams();
const up = useOrderUpdate();
const two = useTwoPane();
const accepting = ref<PharmOrder | null>(null);
const cancelling = ref<PharmOrder | null>(null);
const delivering = ref<PharmOrder | null>(null);
const receiptId = ref<string | null>(null);
// ডেলিভারির আগাম পেমেন্ট (pay_delivery): টাকা সার্ভার অর্ডার থেকে নেয়
const s = useSession();
const pharmId = computed(() => String((s.work.pharmacy as { id?: string } | null)?.id ?? s.uid ?? ''));
const items = computed(() => props.orders.data?.items ?? []);
const counts = computed(() => segCounts(PHARM_SEGS, props.orders.data?.counts, pharmSegOf as (x: never) => PharmSeg, items.value));
// ?s=done বা ?s=closed = শেষ
const seg = computed<PharmSeg>(() => {
  // ?s= (web) or ?seg= (work_today task links, backend MYWORK.md)
  const asked = sp.value.get('s') ?? sp.value.get('seg');
  const k = (asked === 'done' ? 'closed' : asked) as PharmSeg | null;
  return k && PHARM_SEGS.includes(k) ? k : 'new';
});
const qseg = computed(() => (seg.value === 'closed' ? 'done' : seg.value));
const rows = computed(() => items.value.filter((o) => pharmSegOf(o) === seg.value).sort((a, c) => c.createdAt - a.createdAt));
const keep = computed(() => (seg.value === 'new' ? '' : `?s=${qseg.value}`));
const open = (o: PharmOrder) => nav(`${BASE}/rx/${o.code}${keep.value}`);
// ডেস্কটপে বাছা অর্ডার: খোলা কোডেরটা, নইলে চাপা, নইলে প্রথমটা (বাছলে প্রেসক্রিপশন খোলে না — "খুলুন" চাপলে খোলে)
const picked = ref<string | null>(null);
const sel = computed(() => (props.code ? rows.value.find((o) => o.code === props.code) ?? null : rows.value.find((o) => o.id === picked.value) ?? rows.value[0] ?? null));
function pick(o: PharmOrder) {
  picked.value = o.id;
  if (props.code && o.code !== props.code) nav(`${BASE}${keep.value}`);
}
function act(o: PharmOrder, k: OrderAct) {
  if (k === 'open') open(o);
  else if (k === 'ready') up.update(o, 'ready');
  else if (k === 'out') up.update(o, 'out');
  else if (k === 'accept') accepting.value = o;
  else if (k === 'deliver') delivering.value = o;
  else if (k === 'cancel') cancelling.value = o;
  else if (k === 'receipt') receiptId.value = o.invoiceId ?? null;
}
const busyFor = (o: PharmOrder) => up.busyId.value === o.id;
</script>

<template>
  <div class="qd">
    <div class="qd-list">
      <div class="row wrap gap-8">
        <PharmOrderSegs :value="qseg" :counts="counts" />
        <span class="grow" />
        <Button variant="text" small icon="refresh" :busy="orders.isFetching && !orders.isLoading" @click="orders.refetch()">{{ tr('Refresh', 'নতুন করে দেখুন') }}</Button>
      </div>
      <ErrorText :text="up.err.value" />

      <SkeletonCards v-if="orders.isLoading" :n="3" :h="120" />
      <ErrorText v-else-if="orders.error" :text="errorText(orders.error)" />
      <div v-else-if="!rows.length" class="card rxp-empty-card">
        <Icon :name="seg === 'new' ? 'check_circle' : seg === 'active' ? 'local_shipping' : 'history'" />
        <span class="t-title-s">
          {{ seg === 'new' ? tr('No new orders now', 'এখন নতুন কোনো অর্ডার নেই') : seg === 'active' ? tr('Nothing in progress', 'চলমান কোনো অর্ডার নেই') : tr('Nothing finished yet', 'এখনো শেষ হওয়া কোনো অর্ডার নেই') }}
        </span>
        <span v-if="seg === 'new'" class="t-body-s">{{ tr('When a customer scans your shop QR or sends a prescription, it comes here.', 'ক্রেতা দোকানের QR স্ক্যান করলে বা প্রেসক্রিপশন পাঠালে এখানে আসবে।') }}</span>
        <span v-if="seg === 'active'" class="t-body-s">{{ tr('Orders you\'ve accepted or made ready stay here until the medicines are given.', 'যে অর্ডার নিয়েছেন বা তৈরি করেছেন, ওষুধ দেওয়া পর্যন্ত এখানে থাকবে।') }}</span>
      </div>
      <ul v-else-if="two" class="q-list" :aria-label="tr('Orders', 'অর্ডার')">
        <li v-for="o in rows" :key="o.id">
          <button type="button" class="q-item" :aria-current="o.id === sel?.id" data-testid="q-item" @click="pick(o)">
            <span class="col gap-2 grow" :style="{ minWidth: 0 }">
              <span class="t-title-s ellipsis">{{ o.patientName || '—' }}</span>
              <span class="t-body-s muted">{{ (o.mode === 'online' ? tr('Online order', 'অনলাইন অর্ডার') : tr('In the shop', 'দোকানে এসেছেন')) + ' · ' + timeAgo(o.createdAt) }}</span>
            </span>
            <Tag v-if="o.status !== 'sent'" :tone="orderTone(o.status)">{{ orderStatusShop(o.status) }}</Tag>
          </button>
        </li>
      </ul>
      <div v-else class="rxp-cards">
        <OrderCard v-for="o in rows" :key="o.id" :o="o" :on="code === o.code" :busy="busyFor(o)" :disabled="!!up.busyId.value" :pharm-id="pharmId" @act="(k) => act(o, k)" />
      </div>
      <p v-if="!orders.isLoading && counts[seg] > rows.length" class="t-body-s muted">{{ tr(`Showing the latest ${rows.length} of ${counts[seg]}`, `মোট ${num(counts[seg])}টার সাম্প্রতিক ${num(rows.length)}টা দেখানো হলো`) }}</p>
      <BizPreview kind="pharmacy" />
    </div>
    <aside class="qd-side rxp-codeside" :aria-label="tr('Details', 'বিস্তারিত')">
      <section class="card rxp-panel rxp-open-code">
        <div class="row gap-8">
          <span class="rxp-panel-ic"><Icon name="search" /></span>
          <h2 class="t-title-m grow">{{ tr('Open a prescription by code', 'কোড দিয়ে প্রেসক্রিপশন খুলুন') }}</h2>
        </div>
        <CodeBox
          :key="code ?? ''"
          :auto-focus="!code"
          :label="tr('Code under the prescription QR (or paste the link)', 'প্রেসক্রিপশনের QR-এর নিচের কোড (বা লিংক পেস্ট করুন)')"
          @open="(c) => nav(`${BASE}/rx/${c}${keep}`)"
        />
        <p class="t-body-s muted">
          {{ tr('Open with the code and note how many you gave. The patient can get the rest from another pharmacy.', 'কোড দিয়ে খুলে কয়টা দিলেন লিখুন। বাকিটা রোগী অন্য ফার্মেসি থেকে নিতে পারবেন।') }}
        </p>
      </section>
      <OrderCard v-if="two && sel" :key="'d' + sel.id" class="q-detail-card" :o="sel" :on="true" :busy="busyFor(sel)" :disabled="!!up.busyId.value" :pharm-id="pharmId" @act="(k) => act(sel!, k)" />
      <RxPanel v-if="code" :key="code" :code="code" @next="nav(`${BASE}${keep}`)" />
    </aside>
    <AcceptDialog v-if="accepting" :order="accepting" @close="accepting = null" />
    <CancelOrderDialog v-if="cancelling" :order="cancelling" @close="cancelling = null" />
    <DeliverDialog v-if="delivering" :order="delivering" @close="delivering = null" />
    <InvoiceByIdDialog v-if="receiptId" :id="receiptId" @close="receiptId = null" />
  </div>
</template>
