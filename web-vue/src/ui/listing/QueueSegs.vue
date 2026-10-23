<script setup lang="ts">
// "আমার কাজ"-এর কাজের সারি — পাঁচ ধরনেই একই স্থির ট্যাব: নতুন n · চলছে n · শেষ (+ দরকার হলে একটা চতুর্থ, যেমন ফার্মেসির "ওষুধ খোঁজা")।
// সর্বোচ্চ ৪টা, ১–২ শব্দ, পাশে স্ক্রল নেই (৩৯০-এ লেখা কাটে না — জায়গা কম হলে দুই লাইনে), সংখ্যা শুধু কাজ বাকি থাকা ভাগে
// (শেষ = ইতিহাস, সংখ্যা নয়)। কোন জিনিস কোন ভাগে, তা পাতা ঠিক করে (ফার্মেসি: অর্ডার, প্যাথলজি: অনুরোধ ও বাসা থেকে নমুনা,
// অ্যাম্বুলেন্স: ডাক, ডাক্তার: বুকিং)।
import { computed } from 'vue';
import { PillTabs } from '@/ui/kit';
import type { PillTab } from '@/ui/types';
import { LT } from './model';

export type QSeg = 'new' | 'active' | 'done';
const props = defineProps<{ value: string; counts: Partial<Record<QSeg, number>>; label: string; extra?: PillTab | null; labels?: Partial<Record<QSeg, string>> }>();
const emit = defineEmits<{ change: [k: string] }>();
const tabs = computed<PillTab[]>(() => [
  { key: 'new', label: props.labels?.new ?? LT.segNew(), count: props.counts.new },
  { key: 'active', label: props.labels?.active ?? LT.segActive(), count: props.counts.active },
  { key: 'done', label: props.labels?.done ?? LT.segDone() },
  ...(props.extra ? [props.extra] : []),
]);
</script>

<template>
  <div class="rxp-sub-pills q-segs q-fixed" data-testid="queue-segs">
    <PillTabs secondary :label="label" :value="value" :tabs="tabs" @change="(k) => emit('change', k)" />
  </div>
</template>
