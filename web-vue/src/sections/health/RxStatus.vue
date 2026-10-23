<script setup lang="ts">
// প্রেসক্রিপশন এখন কোন অবস্থায়: রঙিন চিপে এক লাইন + নিচে পরীক্ষা/ফলো-আপের ছোট লাইন (backend/docs/RX-STATE.md) — অ্যাপের RxStatusLine
import { computed } from 'vue';
import { IconLine } from '@/ui/kit';
import type { Rx } from './rx';
import { primaryText, secondaryLines } from './rxstate';

const props = defineProps<{ rx: Rx }>();
const main = computed(() => primaryText(props.rx));
const more = computed(() => secondaryLines(props.rx));
</script>

<template>
  <div v-if="main" class="rx-status" data-testid="rx-status">
    <span :class="['rx-chip', main.tone]" data-testid="rx-status-chip"><span class="dot" aria-hidden="true" />{{ main.text }}</span>
    <IconLine v-for="[ic, t] in more" :key="ic" :icon="ic" class="rx-sub" data-testid="rx-status-sub">{{ t }}</IconLine>
  </div>
</template>

<style scoped>
.rx-status { display: flex; flex-direction: column; align-items: flex-start; gap: 6px; margin-top: 10px; }
.rx-chip {
  display: inline-flex; align-items: center; gap: 6px; max-width: 100%;
  border-radius: 16px; padding: 4px 12px; font-size: 13px; line-height: 18px; font-weight: 600;
  background: var(--surface-variant); color: var(--on-surface-variant);
}
.rx-chip .dot { width: 8px; height: 8px; border-radius: 50%; background: currentColor; flex: none; }
.rx-chip.primary { background: var(--primary-container); color: var(--on-primary-container); }
.rx-chip.good { background: var(--tertiary-container); color: var(--on-tertiary-container); }
.rx-chip.info { background: var(--secondary-container); color: var(--on-secondary-container); }
.rx-chip.error { background: var(--error-container); color: var(--on-error-container); }
.rx-chip.muted { background: var(--surface-high); color: var(--on-surface-variant); }
.rx-sub { font-size: 13px; line-height: 18px; }
/* বাছা কার্ডের পটভূমিও secondary-container — চিপ যেন মিশে না যায় */
:global(.card.sel) .rx-chip.info { background: var(--surface-lowest); }
</style>
