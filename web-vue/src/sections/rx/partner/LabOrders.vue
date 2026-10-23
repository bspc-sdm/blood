<script setup lang="ts">
// /work/lab/orders/* — প্যাথলজির ডেস্ক (আগে /doctor প্যানেলে ছিল): পরীক্ষার অনুরোধ, কোড দিয়ে খোলা, নমুনা নেওয়া,
// বাসা থেকে নমুনার সময় নিশ্চিত করা, রিপোর্ট দেওয়া (ছকের ঘর, একক, স্বাভাবিক মাত্রা), ছবি, পরীক্ষা ও দাম (শুধু ওয়েবে),
// আর নিজের মেশিনের স্বাভাবিক মাত্রা
// (অ্যাপের MyLabPanel, LabReportEntryDialog, LabReportView)
import { computed } from 'vue';
import { useRoute } from 'vue-router';
import { useCall } from '@/lib/query';
import { matchPath } from '@/lib/nav';
import { myBusinessTitle } from '../../work/shared';
import LabTabs from '../../work/LabTabs.vue';
import { usePartnerReady } from './common';
import type { LabOrders } from './model';
import PartnerPage from './PartnerPage.vue';
import PartnerGate from './PartnerGate.vue';
import LabDetail from './LabDetail.vue';
import LabRanges from './LabRanges.vue';
import LabPrices from './LabPrices.vue';
import LabList from './LabList.vue';
import LabBill from '../../invoices/LabBill.vue';
import BizInvoiceList from '../../invoices/BizInvoiceList.vue';
import { useSession } from '@/lib/session';

const BASE = '/work/lab/orders';
const route = useRoute();
const ready = usePartnerReady('lab');
const title = computed(() => myBusinessTitle('lab'));
const orders = useCall<LabOrders>('lab_orders', {}, () => ({ enabled: ready.value, refetchInterval: 60_000 }));
const m = (p: string) => matchPath({ path: BASE + p, end: true }, route.path);
const ranges = computed(() => !!m('/ranges'));
const prices = computed(() => !!m('/prices'));
const bill = computed(() => !!m('/bill'));
const invoices = computed(() => !!m('/invoices'));
const detailId = computed(() => (ranges.value || prices.value || bill.value || invoices.value ? undefined : m('/:id')?.params.id));
const s = useSession();
const bizId = computed(() => (s.work.lab as { id?: string } | null)?.id ?? '');
</script>

<template>
  <!-- ২.২৭.০: দুই ট্যাব — "অনুরোধ" (তালিকা, একটা অনুরোধ) আর "ল্যাব" (/work/lab/info, পরীক্ষা ও দাম, স্বাভাবিক মাত্রা) -->
  <PartnerPage :title="title" back-to="/work">
    <PartnerGate kind="lab">
      <LabTabs :active="ranges ? 'ranges' : prices ? 'prices' : bill ? 'bill' : invoices ? 'invoices' : 'orders'" />
      <!-- বিল (অনুরোধ থেকে ?order= বা সরাসরি আসা রোগী) আর রসিদের তালিকা (বাকি আদায়, বাতিল) -->
      <LabBill v-if="bill" />
      <BizInvoiceList v-else-if="invoices && bizId" kind="lab" :biz-id="bizId" />
      <LabRanges v-else-if="ranges" />
      <LabPrices v-else-if="prices" />
      <LabDetail v-else-if="detailId" :key="detailId" :id="detailId" :orders="orders.data?.items ?? null" :orders-loading="orders.isLoading" />
      <LabList v-else :orders="orders" />
    </PartnerGate>
  </PartnerPage>
</template>
