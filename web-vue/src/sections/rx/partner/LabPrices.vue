<script setup lang="ts">
// প্যাথলজি — পরীক্ষা ও দাম (/work/lab/orders/prices, শুধু ওয়েবে — বসে করার কাজ): ছক থেকে পরীক্ষা খুঁজে যোগ বা নিজে লিখে,
// প্রতিটার দাম (৳), রিপোর্ট কতক্ষণে, এখন হয় কি না; সব পরীক্ষায় ছাড় (%), বাসা থেকে নমুনার চার্জ।
// রোগীরা সেবার তালিকায় আর প্রেসক্রিপশন পাঠানোর সময় দেখেন কোথায় কোন পরীক্ষা কত টাকা (server/lib/labprices.js)।
import { ErrorBox, SkeletonCards } from '@/ui/kit';
import { errorText } from '@/lib/api';
import { useCall } from '@/lib/query';
import { tr } from '@/lib/i18n';
import type { LabPriceList, TestDef } from './model';
import LabPricesEditor from './LabPricesEditor.vue';

const q = useCall<LabPriceList>('lab_prices_get', {}, { staleTime: 0 });
const cat = useCall<{ tests: TestDef[] }>('labtests_get', {}, { staleTime: 10 * 60_000 });
</script>

<template>
  <SkeletonCards v-if="q.isLoading" :n="2" :h="220" />
  <ErrorBox v-else-if="q.error || !q.data" :error="q.error ? errorText(q.error) : tr('Not found', 'পাওয়া যায়নি')" :on-retry="() => q.refetch()" />
  <LabPricesEditor v-else :key="q.data.updatedAt" :data="q.data" :tests="(cat.data?.tests ?? []).filter((t) => !t.hidden)" />
</template>
