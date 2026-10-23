<script setup lang="ts">
// ফার্মেসির কাউন্টার আর হিসাব-খাতা (আগে /doctor প্যানেলে ছিল) — তিন ট্যাব, তৃতীয় স্তর নেই (work/PharmacyTabs; আমার কাজ CONTRACT §২):
//  "অর্ডার" — /work/pharmacy/orders (আর /rx/:code, পুরোনো /online): কোড দিয়ে খোলা, নতুন · চলছে · শেষ · ওষুধ খোঁজা (OrdersTab;
//            খোঁজা /work/pharmacy/finds — work/PharmacyFinds)
//  "বিক্রি" — নতুন বিক্রি /shop/sales (কাউন্টার) · রসিদ /shop/invoices
//  "দোকান" — স্টক /shop/stock · মাল কেনা /shop/stock?v=buy · হিসাব /shop/analytics (লাভ-ক্ষতি) · তথ্য /work/pharmacy/shop (work/MyPharmacy)
// পুরোনো লিংক: /work/pharmacy/orders/stock|buy|sales|books → নতুন জায়গায় (replace)।
import { computed } from 'vue';
import { useRoute } from 'vue-router';
import Redirect from '@/shell/Redirect.vue';
import { useCall } from '@/lib/query';
import { matchPath, useSearchParams } from '@/lib/nav';
import { myBusinessTitle } from '../../work/shared';
import PharmacyTabs from '../../work/PharmacyTabs.vue';
import { usePartnerReady } from './common';
import type { PharmOrders } from './model';
import PartnerPage from './PartnerPage.vue';
import PartnerGate from './PartnerGate.vue';
import OrdersTab from './OrdersTab.vue';
import ItemsTab from './books/ItemsTab.vue';
import BuyTab from './books/BuyTab.vue';
import SalesTab from './books/SalesTab.vue';
import ReportTab from './books/ReportTab.vue';
import BizInvoiceList from '../../invoices/BizInvoiceList.vue';
import { deskRole } from '../../invoices/offline';
import { invFeature } from '../../invoices/features';
import { useSession } from '@/lib/session';

const BASE = '/work/pharmacy/orders';
const SHOP = '/work/pharmacy/shop';
/** পুরোনো হিসাব-খাতার ঠিকানা → নতুন */
const OLD: Record<string, string> = { stock: `${SHOP}/stock`, buy: `${SHOP}/stock?v=buy`, sales: `${SHOP}/sales`, books: `${SHOP}/analytics` };

const route = useRoute();
const [sp] = useSearchParams();
const ready = usePartnerReady('pharmacy');
const title = computed(() => myBusinessTitle('pharmacy'));
const old = computed(() => matchPath({ path: `${BASE}/:k`, end: true }, route.path)?.params.k);
const tab = computed(() => {
  const sub = matchPath({ path: `${SHOP}/:k`, end: true }, route.path)?.params.k;
  if (sub === 'stock') return sp.value.get('v') === 'buy' ? 'buy' : 'stock';
  return sub === 'sales' || (sub === 'invoices' && invFeature('inv_pharmacy')) || sub === 'analytics' ? sub : 'orders';
});
const orders = useCall<PharmOrders>('rx_orders_pharmacy', {}, () => ({ enabled: ready.value && tab.value === 'orders', refetchInterval: 60_000 }));
const rxCode = computed(() => matchPath({ path: `${BASE}/rx/:code`, end: true }, route.path)?.params.code ?? null);
const s = useSession();
const bizId = computed(() => (s.work.pharmacy as { id?: string } | null)?.id ?? '');
</script>

<template>
  <Redirect v-if="old && OLD[old]" :to="OLD[old]" />
  <PartnerPage v-else :title="title" back-to="/work">
    <PartnerGate kind="pharmacy">
      <PharmacyTabs :active="tab" />
      <!-- দোকান: স্টক (পণ্য ও স্টক) আর মাল কেনা — দুটোই দোকানের ট্যাবে, আলাদা স্তর নয় -->
      <BuyTab v-if="tab === 'buy'" />
      <ItemsTab v-else-if="tab === 'stock'" />
      <SalesTab v-else-if="tab === 'sales'" />
      <!-- রসিদ: বাকি আদায়, বাতিল (স্টক ফেরে) -->
      <BizInvoiceList v-else-if="tab === 'invoices' && bizId" kind="pharmacy" :biz-id="bizId" />
      <ReportTab v-else-if="tab === 'analytics' && deskRole() !== 'counter'" />
      <template v-else-if="tab === 'analytics'" />
      <OrdersTab v-else :code="rxCode" :orders="orders" />
    </PartnerGate>
  </PartnerPage>
</template>
