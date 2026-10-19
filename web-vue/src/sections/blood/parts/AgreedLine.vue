<script setup lang="ts">
// "৩ জন রাজি · ১ জন নিশ্চিত"
import { computed } from 'vue';
import { num, tr } from '@/lib/i18n';
import { counts, willing, type DonorResponse } from '../model';
import SlotRow from './SlotRow.vue';

const props = defineProps<{ list: DonorResponse[]; slotW?: number }>();
const w = computed(() => willing(props.list));
</script>

<template>
  <SlotRow icon="group_round" :slot-w="slotW ?? 20">
    <span class="t-title-s">{{ tr(`${num(w.length)} agreed · ${num(w.filter(counts).length)} confirmed`, `${num(w.length)} জন রাজি · ${num(w.filter(counts).length)} জন নিশ্চিত`) }}</span>
  </SlotRow>
</template>
