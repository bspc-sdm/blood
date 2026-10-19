<script setup lang="ts">
// দেওয়া রিপোর্ট: মাথার তথ্য তালিকা থেকেই; ফল, ছবি আর আগের ফল পুরো রিপোর্ট থেকে
import { ErrorBox, SkeletonCards } from '@/ui/kit';
import { errorText } from '@/lib/api';
import { useCall } from '@/lib/query';
import { tr } from '@/lib/i18n';
import type { HistoryPoint, LabOrder } from './model';
import ReportView from './ReportView.vue';

const props = defineProps<{ o: LabOrder }>();
const q = useCall<{ item: LabOrder; history: Record<string, HistoryPoint[]> }>('lab_order_get', () => ({ id: props.o.id }));
</script>

<template>
  <SkeletonCards v-if="q.isLoading" :n="1" :h="260" />
  <ErrorBox v-else-if="q.error || !q.data" :error="q.error ? errorText(q.error) : tr('Not found', 'পাওয়া যায়নি')" :on-retry="() => q.refetch()" />
  <ReportView v-else :o="{ ...o, report: q.data.item.report ?? o.report }" :history="q.data.history ?? {}" />
</template>
