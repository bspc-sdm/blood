<script setup lang="ts">
import { computed } from 'vue';
import { PillTabs } from '@/ui/kit';
import { tr } from '@/lib/i18n';
import { useSession } from '@/lib/session';
import { useNav } from '@/lib/nav';
import { T } from '../invoices/text';
import { deskRole } from '../invoices/offline';
import { myBusinessTitle, pharmTabPath, useNewCount, useUnansweredFinds, type PharmTab } from './shared';

const props = defineProps<{ active: PharmTab }>();
const nav = useNav();
const s = useSession();
const approved = computed(() => (s.work.pharmacy as { status?: string } | null)?.status === 'approved');
const fresh = useNewCount('rx_orders_pharmacy', approved);
const finds = useUnansweredFinds(approved);
const top = computed(() => (props.active === 'orders' || props.active === 'finds' ? props.active : 'shop'));
</script>

<template>
  <div v-if="approved" class="col gap-8 work-tabs">
    <PillTabs
      :label="myBusinessTitle('pharmacy')"
      :value="top"
      :tabs="[
        { key: 'orders', label: tr('Orders', 'অর্ডার'), icon: 'medication', count: fresh },
        { key: 'finds', label: tr('Medicine search', 'ওষুধ খোঁজা'), icon: 'search', count: finds },
        { key: 'shop', label: tr('Shop', 'দোকান'), icon: 'storefront' },
      ]"
      @change="(k) => nav(pharmTabPath(k === 'shop' ? 'info' : (k as PharmTab)))"
    />
    <PillTabs
      v-if="top === 'shop'"
      secondary
      :label="tr('Shop', 'দোকান')"
      :value="active"
      :tabs="[
        { key: 'info', label: tr('Info', 'তথ্য'), icon: 'storefront' },
        { key: 'sales', label: tr('Sales', 'বিক্রি'), icon: 'payments' },
        { key: 'invoices', label: T.receipts(), icon: 'list_alt' },
        { key: 'stock', label: tr('Stock', 'স্টক'), icon: 'list_alt' },
        ...(deskRole() === 'counter' ? [] : [{ key: 'analytics', label: tr('Analytics', 'এনালাইটিক্স'), icon: 'history' }]),
      ]"
      @change="(k) => nav(pharmTabPath(k as PharmTab))"
    />
  </div>
</template>
