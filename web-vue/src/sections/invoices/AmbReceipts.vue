<script setup lang="ts">
// অ্যাম্বুলেন্স মালিক/চালক (ওয়েবে শুধু দেখা): ট্রিপ শেষের ভাড়ার রসিদ (ambreq_finish — চালক ফোনের অ্যাপে দেন)।
// একাধিক গাড়ি থাকলে গাড়ি বাছাই; প্রতিটা রসিদ দেখা/ছাপা, বাকি আদায়, বাতিল।
import { computed, ref } from 'vue';
import { Select } from '@/ui/kit';
import { T } from './text';
import BizInvoiceList from './BizInvoiceList.vue';

const props = defineProps<{ cars: { id: string; name: string; vehicleNo?: string }[] }>();
const pick = ref(props.cars[0]?.id ?? '');
const id = computed(() => (props.cars.some((c) => c.id === pick.value) ? pick.value : props.cars[0]?.id ?? ''));
</script>

<template>
  <div v-if="cars.length" class="col gap-8 inv-amb" data-testid="amb-receipts">
    <h2 class="t-title-m">{{ T.tripReceipts() }}</h2>
    <p class="t-body-s muted">{{ T.tripReceiptsNote() }}</p>
    <Select v-if="cars.length > 1" v-model="pick" :aria-label="T.tripReceipts()" :options="cars.map((c) => ({ value: c.id, label: [c.name, c.vehicleNo].filter(Boolean).join(' · ') }))" />
    <BizInvoiceList :key="id" kind="ambulance" :biz-id="id" :title="T.receipts()" initial-period="7d" />
  </div>
</template>
