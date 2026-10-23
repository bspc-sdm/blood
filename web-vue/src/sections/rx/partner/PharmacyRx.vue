<script setup lang="ts">
// ফার্মেসির কাউন্টার আর হিসাব-খাতা (আগে /doctor প্যানেলে ছিল) — ২.৩০.০ থেকে তিন ট্যাব (work/PharmacyTabs; অ্যাপের Pharmacy.kt):
//  "অর্ডার" — /work/pharmacy/orders (আর /rx/:code, পুরোনো /online): কোড দিয়ে খোলা, নতুন · চলছে/তৈরি · শেষ (OrdersTab)
//  "ওষুধ খোঁজা" — /work/pharmacy/finds (work/PharmacyFinds)
//  "দোকান" — তথ্য /work/pharmacy/shop (work/MyPharmacy) · বিক্রি /shop/sales · স্টক /shop/stock (পণ্য ও স্টক, ?v=buy মাল কেনা) ·
//            এনালাইটিক্স /shop/analytics (লাভ-ক্ষতি) — শেষ তিনটা এখানে।
// পুরোনো লিংক: /work/pharmacy/orders/stock|buy|sales|books → নতুন জায়গায় (replace)।
import { computed } from 'vue';
import { useRoute } from 'vue-router';
import { PillTabs } from '@/ui/kit';
import Redirect from '@/shell/Redirect.vue';
import { tr } from '@/lib/i18n';
import { useCall } from '@/lib/query';
import { matchPath, useNav, useSearchParams } from '@/lib/nav';
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
import { useSession } from '@/lib/session';

const BASE = '/work/pharmacy/orders';
const SHOP = '/work/pharmacy/shop';
/** পুরোনো হিসাব-খাতার ঠিকানা → নতুন */
const OLD: Record<string, string> = { stock: `${SHOP}/stock`, buy: `${SHOP}/stock?v=buy`, sales: `${SHOP}/sales`, books: `${SHOP}/analytics` };

const route = useRoute();
const nav = useNav();
const [sp] = useSearchParams();
const ready = usePartnerReady('pharmacy');
const title = computed(() => myBusinessTitle('pharmacy'));
const old = computed(() => matchPath({ path: `${BASE}/:k`, end: true }, route.path)?.params.k);
const tab = computed(() => {
  const sub = matchPath({ path: `${SHOP}/:k`, end: true }, route.path)?.params.k;
  return sub === 'sales' || sub === 'invoices' || sub === 'stock' || sub === 'analytics' ? sub : 'orders';
});
const orders = useCall<PharmOrders>('rx_orders_pharmacy', {}, () => ({ enabled: ready.value && tab.value === 'orders', refetchInterval: 60_000 }));
const rxCode = computed(() => matchPath({ path: `${BASE}/rx/:code`, end: true }, route.path)?.params.code ?? null);
const buy = computed(() => sp.value.get('v') === 'buy');
const s = useSession();
const bizId = computed(() => (s.work.pharmacy as { id?: string } | null)?.id ?? '');
</script>

<template>
  <Redirect v-if="old && OLD[old]" :to="OLD[old]" />
  <PartnerPage v-else :title="title" back-to="/work">
    <PartnerGate kind="pharmacy">
      <PharmacyTabs :active="tab" />
      <template v-if="tab === 'stock'">
        <!-- স্টক: পণ্য ও স্টকের তালিকা আর মাল কেনা -->
        <div class="rxp-sub-pills">
          <PillTabs
            secondary
            :label="tr('Stock', 'স্টক')"
            :value="buy ? 'buy' : 'items'"
            :tabs="[
              { key: 'items', label: tr('Products & stock', 'পণ্য ও স্টক'), icon: 'list_alt' },
              { key: 'buy', label: tr('Stock in', 'মাল কেনা'), icon: 'storefront' },
            ]"
            @change="(k) => nav(k === 'buy' ? `${SHOP}/stock?v=buy` : `${SHOP}/stock`, { replace: true })"
          />
        </div>
        <BuyTab v-if="buy" />
        <ItemsTab v-else />
      </template>
      <SalesTab v-else-if="tab === 'sales'" />
      <!-- রসিদ: বাকি আদায়, বাতিল (স্টক ফেরে) -->
      <BizInvoiceList v-else-if="tab === 'invoices' && bizId" kind="pharmacy" :biz-id="bizId" />
      <ReportTab v-else-if="tab === 'analytics' && deskRole() !== 'counter'" />
      <template v-else-if="tab === 'analytics'" />
      <OrdersTab v-else :code="rxCode" :orders="orders" />
    </PartnerGate>
  </PartnerPage>
</template>
