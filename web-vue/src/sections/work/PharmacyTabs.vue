<script setup lang="ts">
// ফার্মেসির ট্যাব (আমার কাজ CONTRACT §২): উপরে তিনটা — অর্ডার · বিক্রি · দোকান; তৃতীয় স্তর নেই।
//  অর্ডার — নতুন · চলছে · শেষ · ওষুধ খোঁজা (ভাগগুলো OrdersTab/PharmacyFinds-এর নিজের সারিতে, এখানে নয়)
//  বিক্রি — নতুন বিক্রি (কাউন্টার, /shop/sales) · রসিদ (/shop/invoices, inv_pharmacy চালু থাকলে)
//  দোকান — স্টক · মাল কেনা · হিসাব (কাউন্টারের লোক বাদে) · তথ্য
import { computed } from 'vue';
import { PillTabs } from '@/ui/kit';
import { tr } from '@/lib/i18n';
import { useSession } from '@/lib/session';
import { useNav } from '@/lib/nav';
import { T } from '../invoices/text';
import { deskRole } from '../invoices/offline';
import { invFeature } from '../invoices/features';
import { myBusinessTitle, pharmTabPath, pharmTop, useNewCount, type PharmTab } from './shared';

const props = defineProps<{ active: PharmTab }>();
const nav = useNav();
const s = useSession();
const approved = computed(() => (s.work.pharmacy as { status?: string } | null)?.status === 'approved');
const fresh = useNewCount('rx_orders_pharmacy', approved);
const top = computed(() => pharmTop(props.active));
const saleTabs = computed(() => (invFeature('inv_pharmacy') ? [{ key: 'sales', label: tr('New sale', 'নতুন বিক্রি'), icon: 'payments' }, { key: 'invoices', label: T.receipts(), icon: 'list_alt' }] : [])); // bn-ok
const shopTabs = computed(() => [
  { key: 'stock', label: tr('Stock', 'স্টক'), icon: 'list_alt' },
  { key: 'buy', label: tr('Stock in', 'মাল কেনা'), icon: 'storefront' },
  ...(deskRole() === 'counter' ? [] : [{ key: 'analytics', label: tr('Accounts', 'হিসাব'), icon: 'history' }]),
  { key: 'info', label: tr('Info', 'তথ্য'), icon: 'storefront' },
]);
</script>

<template>
  <div v-if="approved" class="col gap-8 work-tabs">
    <div class="q-fixed even" data-testid="pharmacy-tabs">
      <PillTabs
        :label="myBusinessTitle('pharmacy')"
        :value="top"
        :tabs="[
          { key: 'orders', label: tr('Orders', 'অর্ডার'), icon: 'medication', count: fresh },
          { key: 'sales', label: tr('Sales', 'বিক্রি'), icon: 'payments' },
          { key: 'shop', label: tr('Shop', 'দোকান'), icon: 'storefront' },
        ]"
        @change="(k) => nav(pharmTabPath(k === 'shop' ? 'stock' : (k as PharmTab)))"
      />
    </div>
    <div v-if="top === 'sales' && saleTabs.length" class="q-fixed even">
      <PillTabs secondary :label="tr('Sales', 'বিক্রি')" :value="active" :tabs="saleTabs" @change="(k) => nav(pharmTabPath(k as PharmTab))" />
    </div>
    <div v-if="top === 'shop'" class="q-fixed even" data-testid="shop-tabs">
      <PillTabs secondary :label="tr('Shop', 'দোকান')" :value="active" :tabs="shopTabs" @change="(k) => nav(pharmTabPath(k as PharmTab))" />
    </div>
  </div>
</template>
