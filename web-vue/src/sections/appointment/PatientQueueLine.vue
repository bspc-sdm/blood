<script setup lang="ts">
// তালিকার কার্ডে সিরিয়ালের এক লাইন
import { computed } from 'vue';
import { num, tr } from '@/lib/i18n';
import type { Appointment } from './model';
import { arriveText, queueLive, stateText, usePatientQueue } from './queue';

const props = defineProps<{ a: Appointment }>();
const query = usePatientQueue(() => props.a);
const text = computed(() => {
  const q = query.data;
  if (!queueLive(props.a) || !q?.live) return null;
  return (
    stateText(q) ??
    [
      q.started ? tr(`Now: #${q.current}`, `এখন চলছে: ${num(q.current ?? 0)}`) : tr('Not started', 'এখনো শুরু হয়নি'),
      tr(`${q.ahead} before you`, `আপনার আগে ${num(q.ahead ?? 0)} জন`),
      arriveText(q),
    ]
      .filter(Boolean)
      .join(' · ')
  );
});
</script>

<template>
  <span v-if="text != null" class="t-body-s appt-q-line">{{ text }}</span>
</template>
