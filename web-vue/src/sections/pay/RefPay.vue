<script setup lang="ts">
// একটা জিনিসের (অ্যাপয়েন্টমেন্ট, অনলাইন অর্ডার, পরীক্ষা, অ্যাম্বুলেন্সের ট্রিপ, রসিদ) পেমেন্টের বোতাম — রোগীর দিক।
// জিনিসের সাথে পেমেন্ট এলে (payRequest / payRequestId) সেটাই; নইলে pay_request_get {refType, refId, purpose} দিয়ে খোঁজা
// (না থাকলে কিছু দেখায় না); start দিলে না থাকলেও শুরু করা যায় (রসিদের বাকি: pay_due_start)।
// কোন সুইচে দেখাবে তা ডাকার জায়গা ঠিক করে (payOn) — রক্তের অংশে কখনো নয়।
import { computed } from 'vue';
import { ServerError } from '@/lib/api';
import { useQ } from '@/lib/query';
import { useCareMode } from '@/lib/care';
import { useSession } from '@/lib/session';
import * as api from './api';
import { sseLive } from './live';
import type { PayLink, PayPurpose, PayRequest } from './model';
import PayButton from './PayButton.vue';

const props = withDefaults(
  defineProps<{ refType: string; refId: string; purpose?: PayPurpose; link?: PayLink | null; start?: (() => Promise<PayRequest | null>) | null; label?: string; note?: string | null }>(),
  { purpose: undefined, link: null, start: null, label: undefined, note: null },
);
const s = useSession();
const care = useCareMode();
const q = useQ<PayRequest | null>(() => ({
  queryKey: ['pay_request_get', 'ref', props.refType, props.refId, props.purpose ?? '', care.value?.uid ?? null, s.uid],
  queryFn: () => api.getRequestByRef(props.refType, props.refId, props.purpose).catch((e) => {
    if (e instanceof ServerError && (e.status === 404 || e.code === 'feature_off')) return null;
    throw e;
  }),
  enabled: !props.link && !!props.refId && !s.guest,
  staleTime: 15_000,
  retry: false,
  refetchInterval: () => (sseLive.value ? false : 60_000),
}));
const link = computed<PayLink | null>(() => props.link ?? (q.data ? { id: q.data.id, status: q.data.status, amount: q.data.amount, request: q.data } : null));
</script>

<template>
  <PayButton v-if="link || start" :key="link?.id ?? 'start'" :link="link" :start="start" :label="label" :note="note" :purpose="purpose" @changed="() => q.refetch()" />
</template>
