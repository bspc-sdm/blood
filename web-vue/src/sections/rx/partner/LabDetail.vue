<script setup lang="ts">
// তালিকায় না থাকলে (পুরোনো) সরাসরি আনা
import { computed } from 'vue';
import { ErrorBox, SkeletonCards } from '@/ui/kit';
import { errorText } from '@/lib/api';
import { useCall } from '@/lib/query';
import { tr } from '@/lib/i18n';
import type { HistoryPoint, LabOrder } from './model';
import OrderView from './OrderView.vue';

const props = defineProps<{ id: string; orders: LabOrder[] | null; ordersLoading: boolean }>();
const fromList = computed(() => props.orders?.find((o) => o.id === props.id) ?? null);
const direct = useCall<{ item: LabOrder; history: Record<string, HistoryPoint[]> }>('lab_order_get', () => ({ id: props.id }), () => ({ enabled: !props.ordersLoading && !fromList.value }));
const o = computed(() => fromList.value ?? direct.data?.item ?? null);
</script>

<template>
  <OrderView v-if="o" :o="o" />
  <SkeletonCards v-else-if="ordersLoading || direct.isLoading" :n="2" :h="180" />
  <ErrorBox v-else :error="direct.error ? errorText(direct.error) : tr('Not found', 'পাওয়া যায়নি')" />
</template>
