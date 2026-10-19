<script setup lang="ts">
// "এই ভাড়ার মধ্যে Nটা খালি গাড়ি আছে" / ০ হলে "৳X পর্যন্ত বাড়ান" আর "ভাড়া জানা নেই এমন গাড়িও"
import { computed } from 'vue';
import { Button, Icon } from '@/ui/kit';
import { num, tr } from '@/lib/i18n';
import { taka } from './fare';
import type { FareCount } from './ambreq';

const props = defineProps<{ count: FareCount | null; counting: boolean; dest: boolean; includeUnknown: boolean }>();
const emit = defineEmits<{ raise: [to: number | null]; unknown: [] }>();
const night = computed(() => (props.count?.night ? tr(' (night fare included)', ' (রাতের ভাড়াসহ)') : ''));
</script>

<template>
  <span v-if="!dest" class="t-body-s muted">{{ tr('Choose where to go to see how many are free.', 'কয়টা খালি গাড়ি আছে দেখতে "কোথায় যাবেন" বেছে নিন।') }}</span>
  <template v-else-if="!count"><span v-if="counting" class="t-body-s muted">{{ tr('Counting…', 'গোনা হচ্ছে…') }}</span></template>
  <div v-else-if="count.count > 0" class="amb-count ok" role="status" data-testid="amb-count">
    <Icon name="check_circle" />
    <span>{{ tr(`${count.count} free ambulance${count.count > 1 ? 's' : ''} within this fare`, `এই ভাড়ার মধ্যে ${num(count.count)}টা খালি গাড়ি আছে`) + night }}</span>
  </div>
  <div v-else class="amb-count none col gap-8" role="status" data-testid="amb-count">
    <span class="row gap-8"><Icon name="warning" /><span>{{ tr('No ambulance within this fare — raise the fare', 'এই ভাড়ায় কোনো গাড়ি নেই — ভাড়া বাড়ান') }}</span></span>
    <div class="row wrap gap-8">
      <Button v-if="count.raiseTo != null" small icon="add" data-testid="amb-raise" @click="emit('raise', count.raiseTo)">{{ tr(`Raise to ${taka(count.raiseTo)}`, `${taka(count.raiseTo)} পর্যন্ত বাড়ান`) }}</Button>
      <Button v-if="!includeUnknown && count.unknown > 0" small variant="outline" @click="emit('unknown')">{{ tr(`Include ${count.unknown} with unknown fare`, `ভাড়া জানা নেই এমন ${num(count.unknown)}টাও নিন`) }}</Button>
    </div>
  </div>
</template>
