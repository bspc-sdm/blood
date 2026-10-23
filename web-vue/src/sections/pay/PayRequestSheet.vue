<script setup lang="ts">
// একটা পেমেন্ট (pay_request_get) — আনা, দেখানো, বদলালে নতুন করে (SSE pay_* এলেও)।
// initial দিলে আগে সেটাই দেখায় (যেমন তালিকা বা pay_due_start-এর উত্তর থেকে)।
import { computed } from 'vue';
import { ErrorBox, SkeletonCards } from '@/ui/kit';
import { errorText } from '@/lib/api';
import { useQ, queryClient } from '@/lib/query';
import { useCareMode } from '@/lib/care';
import { useSession } from '@/lib/session';
import * as api from './api';
import { usePayLive, sseLive } from './live';
import type { PayRequest } from './model';
import PaySheet from './PaySheet.vue';

const props = defineProps<{ id: string; initial?: PayRequest | null; bare?: boolean; locked?: boolean }>();
const emit = defineEmits<{ changed: [req: PayRequest | null]; loaded: [req: PayRequest] }>();
const care = useCareMode();
const s = useSession();
usePayLive();
const key = computed(() => ['pay_request_get', props.id, care.value?.uid ?? null, s.uid]);
const q = useQ<PayRequest>(() => ({
  queryKey: key.value,
  queryFn: () => api.getRequest(props.id).then((r) => {
    emit('loaded', r);
    return r;
  }),
  initialData: props.initial && props.initial.id === props.id ? props.initial : undefined,
  // শুরুর তথ্য থাকলেও একবার সার্ভার থেকে নতুন; SSE না চললে খোলা অবস্থায় মাঝে মাঝে দেখা
  initialDataUpdatedAt: 0,
  staleTime: 5_000,
  refetchInterval: () => (sseLive.value ? false : 30_000),
}));
function changed(r: PayRequest | null) {
  if (r) queryClient.setQueryData(key.value, r);
  void api.invalidatePay();
  emit('changed', r);
}
</script>

<template>
  <PaySheet v-if="q.data" :req="q.data" :bare="bare" :locked="locked" @changed="changed" />
  <SkeletonCards v-else-if="q.isPending" :n="1" :h="200" />
  <ErrorBox v-else :error="q.error ? errorText(q.error) : ''" :on-retry="() => q.refetch()" />
</template>
