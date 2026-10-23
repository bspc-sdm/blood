<script setup lang="ts">
// ফার্মেসি — "অর্ডার" ট্যাব (২.২৭.০, পারভেজ "দুটোই সাজাও"; অ্যাপের MyWorkTabs.kt-এর PharmacyOrdersTab):
// উপরে "কোড দিয়ে প্রেসক্রিপশন খুলুন" (কোড লিখে/পেস্ট করে সাথে সাথে খোলে), তারপর নতুন · চলছে/তৈরি · শেষ (সংখ্যাসহ),
// দোকানে আসা আর অনলাইন অর্ডার একই তালিকায় — প্রতিটায় পরিষ্কার লেখা "দোকানে এসেছেন" / "অনলাইন অর্ডার", নতুন আগে।
// ভাগ সার্ভার ঠিক করে (rx_orders_pharmacy-এর seg, counts)। ?s=active|closed — নইলে "নতুন"।
import { computed, ref } from 'vue';
import { Button, ErrorText, Icon, PillTabs, SkeletonCards, Tag, cx } from '@/ui/kit';
import { errorText } from '@/lib/api';
import { num, tr } from '@/lib/i18n';
import { formatDateTime, timeAgo } from '@/lib/format';
import { useNav, useSearchParams } from '@/lib/nav';
import { PHARM_SEGS, isOpenOrder, orderStatusShop, orderTone, pharmSegLabel, pharmSegOf, segCounts, type PharmOrder, type PharmOrders, type PharmSeg } from './model';
import { useOrderUpdate } from './online';
import CodeBox from './CodeBox.vue';
import ModeTag from './ModeTag.vue';
import RxPanel from './RxPanel.vue';
import AcceptDialog from './AcceptDialog.vue';
import CancelOrderDialog from './CancelOrderDialog.vue';
import ContactActions from '@/calls/ContactActions.vue';

const BASE = '/work/pharmacy/orders';
const props = defineProps<{ code: string | null; orders: { data?: PharmOrders; isLoading: boolean; isFetching: boolean; error: Error | null; refetch: () => unknown } }>();
const nav = useNav();
const [sp, setSp] = useSearchParams();
const up = useOrderUpdate();
const accepting = ref<PharmOrder | null>(null);
const cancelling = ref<PharmOrder | null>(null);
const items = computed(() => props.orders.data?.items ?? []);
const counts = computed(() => segCounts(PHARM_SEGS, props.orders.data?.counts, pharmSegOf as (x: never) => PharmSeg, items.value));
const seg = computed<PharmSeg>(() => {
  const asked = sp.value.get('s') as PharmSeg | null;
  return asked && PHARM_SEGS.includes(asked) ? asked : 'new';
});
const rows = computed(() => items.value.filter((o) => pharmSegOf(o) === seg.value).sort((a, c) => c.createdAt - a.createdAt));
const keep = computed(() => (seg.value === 'new' ? '' : `?s=${seg.value}`));
const open = (o: PharmOrder) => nav(`${BASE}/rx/${o.code}${keep.value}`);
</script>

<template>
  <div class="col gap-16">
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
    <RxPanel v-if="code" :key="code" :code="code" @next="nav(`${BASE}${keep}`)" />

    <div class="row wrap gap-8">
      <div class="rxp-sub-pills">
        <PillTabs
          secondary
          :label="tr('Orders', 'অর্ডার')"
          :value="seg"
          :tabs="PHARM_SEGS.map((s) => ({ key: s, label: pharmSegLabel(s), count: counts[s] }))"
          @change="(k) => setSp(k === 'new' ? {} : { s: k }, { replace: true })"
        />
      </div>
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
    <template v-else>
      <div class="rxp-cards">
        <article v-for="o in rows" :key="o.id" :class="cx('card col gap-6 rxp-ocard', code === o.code && 'on')">
          <div class="row gap-8">
            <span class="t-title-s grow">{{ o.patientName || '—' }}</span>
            <ModeTag :remote="o.mode === 'online'" :icon="o.mode === 'online' ? 'local_shipping' : 'storefront'">{{ o.mode === 'online' ? tr('Online order', 'অনলাইন অর্ডার') : tr('In the shop', 'দোকানে এসেছেন') }}</ModeTag>
          </div>
          <div class="row wrap gap-6">
            <Tag :tone="orderTone(o.status)">{{ orderStatusShop(o.status) }}</Tag>
            <span class="t-body-s muted" :title="formatDateTime(o.createdAt)">{{ timeAgo(o.createdAt) }}</span>
          </div>
          <span v-if="o.mode === 'online' && (o.price || o.eta)" class="icon-line"><Icon name="payments" /><span>{{ [o.price, o.eta].filter(Boolean).join(' · ') }}</span></span>
          <span v-if="o.mode === 'online' && o.address" class="icon-line"><Icon name="location_on" /><span>{{ o.address }}</span></span>
          <span v-if="o.mode === 'online' && o.phone" class="icon-line"><Icon name="call" /><a class="rxp-phone num" :href="`tel:${o.phone}`">{{ num(o.phone) }}</a></span>
          <ContactActions ctx="rx_order" :ref-id="o.id" :peer-name="o.patientName" :phone="o.phone || null" :show="o.mode === 'online' && isOpenOrder(o)" />
          <span v-if="o.mode === 'online' && o.deliveryFee && isOpenOrder(o)" class="icon-line"><Icon name="local_shipping" /><span>{{ tr('Delivery charge: ', 'ডেলিভারি চার্জ: ') }}{{ o.deliveryFee }}</span></span>
          <span v-if="o.note" class="icon-line"><Icon name="edit_note" /><span>{{ o.note }}</span></span>
          <span v-if="o.missed.length" class="t-body-s c-error">{{ tr('Not available: ', 'নেই: ') }}{{ o.missed.join(', ') }}</span>
          <div v-if="isOpenOrder(o)" class="row wrap gap-6 rxp-row-actions">
            <Button small @click="open(o)">{{ o.mode === 'online' ? tr('Open & note medicines', 'খুলে ওষুধ লিখুন') : tr('Open', 'খুলুন') }}</Button>
            <Button v-if="o.mode !== 'online' && o.status === 'sent'" small variant="tonal" :busy="up.busyId.value === o.id" :disabled="!!up.busyId.value" @click="up.update(o, 'ready')">{{ tr('Ready', 'তৈরি') }}</Button>
            <Button v-if="o.mode === 'online' && o.status === 'sent'" small variant="tonal" :disabled="!!up.busyId.value" @click="accepting = o">{{ tr('Accept', 'অর্ডার নিলাম') }}</Button>
            <Button v-if="o.mode === 'online' && (o.status === 'sent' || o.status === 'accepted')" small variant="outline" :busy="up.busyId.value === o.id" :disabled="!!up.busyId.value" @click="up.update(o, 'out')">{{ tr('Sent out', 'পাঠিয়ে দিয়েছি') }}</Button>
            <Button small variant="danger-text" :disabled="!!up.busyId.value" @click="cancelling = o">{{ tr('Can\'t', 'পারব না') }}</Button>
          </div>
        </article>
      </div>
      <p v-if="counts[seg] > rows.length" class="t-body-s muted">{{ tr(`Showing the latest ${rows.length} of ${counts[seg]}`, `মোট ${num(counts[seg])}টার সাম্প্রতিক ${num(rows.length)}টা দেখানো হলো`) }}</p>
    </template>
    <AcceptDialog v-if="accepting" :order="accepting" @close="accepting = null" />
    <CancelOrderDialog v-if="cancelling" :order="cancelling" @close="cancelling = null" />
  </div>
</template>
