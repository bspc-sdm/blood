<script setup lang="ts">
// তালিকার উপরে: "অ্যাম্বুলেন্স ডাকুন" (কাছের খালি গাড়িগুলোতে একসাথে) আর পাশে "ফোন" — সবচেয়ে কাছের খালি গাড়িতে
// এক চাপে (সাধারণ "ফোন করুন"-এর মতোই লেখা হয়); নিচে নাম, দূরত্ব, ধরন
import { computed } from 'vue';
import { tr } from '@/lib/i18n';
import { useSession } from '@/lib/session';
import { ambulanceKm, ambulanceTypeLabel, recordCall, type Ambulance, type Lists } from './data';
import CallBar from './CallBar.vue';

const props = defineProps<{ list: Ambulance[]; id?: string | null; byArea: boolean; lists: Lists | null }>();
const emit = defineEmits<{ request: [] }>();
const s = useSession();
const a = computed(() => (props.id ? props.list.find((x) => x.id === props.id) : undefined));
// এলাকা ধরে হলে দূরত্ব নয় (জানা নেই), শুধু "এলাকা ধরে"
const sub = computed(() =>
  a.value ? [a.value.name, props.byArea ? tr('By area', 'এলাকা ধরে') : ambulanceKm(a.value), a.value.types.map((t) => ambulanceTypeLabel(props.lists, t)).join(', ')].filter(Boolean).join(' · ') : null,
);
</script>

<template>
  <CallBar :nearest="a" :nearest-sub="sub" :on-call-nearest="a ? () => recordCall('ambulance', a!.id, s.guest) : undefined" @request="emit('request')" />
</template>
