<script setup lang="ts">
// প্যাথলজির বিস্তারিত: এলাকার তালিকায় শুধু দামের সারাংশ, পুরো পরীক্ষা ও দামের তালিকা এখানে আলাদা করে
// (lab_prices_public — অতিথিও পারেন; সার্ভারে এক মিনিট মেমোরিতে, এখানে এক মিনিট রাখা)।
import { computed } from 'vue';
import { useCall } from '@/lib/query';
import type { PublicPrices } from '@/lib/labprice';
import RxSendFromPlace from '@/sections/health/RxSendFromPlace.vue';
import { labEntry, type Lab } from './data';
import EntryDetail from './EntryDetail.vue';

const props = defineProps<{ lab: Lab; backTo: string }>();
const q = useCall<{ prices: PublicPrices | null }>('lab_prices_public', () => ({ labId: props.lab.id }), () => ({ enabled: !!props.lab.prices, staleTime: 60 * 1000 }));
const full = computed(() => (!props.lab.prices ? null : q.data ? q.data.prices : q.isError ? null : undefined));
</script>

<template>
  <EntryDetail :e="labEntry(lab, full)" :back-to="backTo">
    <template #main><RxSendFromPlace :lab="lab" /></template>
  </EntryDetail>
</template>
