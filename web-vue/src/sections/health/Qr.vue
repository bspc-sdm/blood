<script setup lang="ts">
import { computed } from 'vue';
import { tr } from '@/lib/i18n';
import { qrMatrix } from './qr';

const props = defineProps<{ text: string; size: number }>();
const m = computed(() => qrMatrix(props.text));
const n = computed(() => (m.value ? m.value.length + 2 : 0));
const d = computed(() => {
  let out = '';
  m.value?.forEach((row, y) => row.forEach((on, x) => {
    if (on) out += `M${x + 1} ${y + 1}h1v1h-1z`;
  }));
  return out;
});
</script>

<template>
  <svg v-if="m" class="health-qr" :viewBox="`0 0 ${n} ${n}`" :width="size" :height="size" role="img" :aria-label="tr('Verification QR', 'যাচাইয়ের QR')" shape-rendering="crispEdges">
    <rect :width="n" :height="n" class="bg" />
    <path :d="d" class="fg" />
  </svg>
</template>
