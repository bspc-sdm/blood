<script setup lang="ts">
// ফার্মেসির "অর্ডার"-এর স্থির ভাগ: নতুন n · চলছে n · শেষ · ওষুধ খোঁজা n — অর্ডারের পাতা আর "ওষুধ খোঁজা"-র পাতা দুটোতেই একই সারি।
// counts না দিলে (খোঁজার পাতা) অর্ডারের তালিকা থেকে নিজেই (একই চাবি — আবার আনা নয়)।
import { computed } from 'vue';
import { tr } from '@/lib/i18n';
import { useCall } from '@/lib/query';
import { useNav, useSearchParams } from '@/lib/nav';
import QueueSegs from '@/ui/listing/QueueSegs.vue';
import { useUnansweredFinds } from '../../work/shared';
import { PHARM_SEGS, pharmSegOf, segCounts, type PharmOrders, type PharmSeg } from './model';

const props = defineProps<{ value: string; counts?: Record<PharmSeg, number> }>();
const nav = useNav();
const [, setSp] = useSearchParams();
const finds = useUnansweredFinds(true);
const own = useCall<PharmOrders>('rx_orders_pharmacy', {}, () => ({ enabled: !props.counts, refetchInterval: 60_000 }));
const c = computed(() => props.counts ?? segCounts(PHARM_SEGS, own.data?.counts, pharmSegOf as (x: never) => PharmSeg, own.data?.items ?? []));
function change(k: string) {
  if (k === 'finds') nav('/work/pharmacy/finds');
  else if (props.value === 'finds') nav('/work/pharmacy/orders' + (k === 'new' ? '' : `?s=${k}`));
  else setSp(k === 'new' ? {} : { s: k }, { replace: true });
}
</script>

<template>
  <QueueSegs
    :label="tr('Orders', 'অর্ডার')"
    :value="value"
    :counts="{ new: c.new, active: c.active }"
    :extra="{ key: 'finds', label: tr('Medicine search', 'ওষুধ খোঁজা'), count: finds }"
    @change="change"
  />
</template>
