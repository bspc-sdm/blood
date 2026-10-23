<script setup lang="ts">
// রক্তের ব্যাগের ছবিতে সংখ্যা (অ্যাপের BloodBag)
import { num, tr } from '@/lib/i18n';

withDefaults(defineProps<{ n: number; filled?: boolean; label?: string }>(), { filled: true });
</script>

<template>
  <svg width="30" height="40" viewBox="0 0 30 40" role="img" :aria-label="label ?? tr(`${n} bag(s) needed`, `${num(n)} ব্যাগ দরকার`)" style="flex: none">
    <line x1="15" y1="0" x2="15" y2="6.4" stroke="var(--error)" stroke-width="1.6" />
    <rect x="10.8" y="4.8" width="8.4" height="4" rx="2" fill="var(--error)" />
    <rect x=".8" y="8" width="28.4" height="31.2" rx="7" :fill="filled ? 'var(--error)' : 'var(--error-a12)'" stroke="var(--error)" stroke-width="1.6" />
    <text x="15" y="29" text-anchor="middle" :fill="filled ? 'var(--on-error)' : 'var(--error)'" font-size="14" font-weight="700" font-family="'Anek Bangla', 'Noto Sans Bengali', sans-serif">{{ num(n) }}</text>
  </svg>
</template>
