<script setup lang="ts">
// ছোট বার চার্ট: গত ৩০ দিনে দিনে কতবার ফোন
import { computed } from 'vue';
import { num, tr } from '@/lib/i18n';

const props = defineProps<{ values: number[] }>();
const max = computed(() => Math.max(1, ...props.values));
const top = computed(() => (props.values.length ? Math.max(...props.values) : 0));
</script>

<template>
  <div class="col gap-4">
    <div class="work-bars" role="img" :aria-label="tr('Calls per day, last 30 days', 'গত ৩০ দিনে দিনে কতবার ফোন')">
      <span v-for="(v, i) in values" :key="i" :title="num(v)" :style="{ height: v ? `${Math.max(4, (v / max) * 100)}%` : '0px' }" />
    </div>
    <div class="row t-label-s muted">
      <span class="grow">{{ tr('30 days ago', '৩০ দিন আগে') }}</span>
      <span>{{ tr('Today', 'আজ') }}</span>
    </div>
    <span class="t-body-s muted">{{ tr(`Highest in a day: ${top}`, `এক দিনে সর্বোচ্চ: ${num(top)}`) }}</span>
  </div>
</template>
