<script setup lang="ts">
// ডাক্তারের "আজকের রোগী" (আর চেম্বারের ডেস্কে সহকারী): "রোগীরা আপনাকে যেভাবে দেখেন" এক লাইনে ("দেখুন" → রোগীদের তালিকার
// একই কার্ড; chamber_days.card = providers-এর হুবহু উত্তর: nextSlot, feeAmount, রেটিং …), আর আজ যে চেম্বারগুলো বসে তার প্রতিটার
// অবস্থা — "আজ সিরিয়াল চলছে / বিরতি / আজ বন্ধ" শুধু দেখায়, "বদলান" → /work (বদলানো এক জায়গায়, আমার কাজ CONTRACT §১.২)।
// সহকারী: শুধু যে চেম্বারের অনুমতি আছে (সার্ভার সেগুলোই দেয়)। পুরোনো সার্ভারে chamber_days নেই → provider_me-র কার্ড, অবস্থা নেই।
import { computed } from 'vue';
import { useCall } from '@/lib/query';
import { isBangla } from '@/lib/i18n';
import { formatTime } from '@/lib/format';
import QueuePreview from '@/sections/work/QueuePreview.vue';
import StateLine from '@/sections/work/StateLine.vue';
import { TT } from '@/sections/work/today/text';
import { LT } from '@/ui/listing/model';
import { parseProvider } from '../model';
import { doctorListing } from '../listing';

interface DayItem { chamberIndex: number; chamberKey?: string; name: string; nameBn?: string; online?: boolean; appointments?: boolean; sitsToday?: boolean; closedToday?: boolean; pausedUntil?: number | null }
const props = defineProps<{ doctorUid?: string | null; fallbackCard?: any | null }>();
const days = useCall<{ items?: DayItem[]; card?: any | null; role?: string }>('chamber_days', () => (props.doctorUid ? { doctorUid: props.doctorUid } : {}), { retry: false, staleTime: 0 });
const card = computed(() => days.data?.card ?? props.fallbackCard ?? null);
const listing = computed(() => (card.value ? doctorListing(parseProvider(card.value), card.value) : null));
const today = computed(() => (days.data?.items ?? []).filter((d) => d.sitsToday && d.appointments !== false));
const chName = (d: DayItem) => (isBangla() && d.nameBn ? d.nameBn : d.name);
const state = (d: DayItem): { label: string; tone: 'good' | 'warn' | 'error' } =>
  d.closedToday
    ? { label: LT.serialOff(), tone: 'error' }
    : d.pausedUntil && d.pausedUntil > Date.now()
      ? { label: TT.reopensAt(formatTime(d.pausedUntil)), tone: 'warn' }
      : { label: LT.serialOn(), tone: 'good' };
</script>

<template>
  <QueuePreview :l="listing" :loading="days.isLoading" :public-to="card?.uid ? `/services/doctor/${card.uid}` : null">
    <StateLine v-for="d in today" :key="d.chamberIndex" :name="today.length > 1 ? chName(d) : undefined" :label="state(d).label" :tone="state(d).tone" :data-chamber="d.chamberIndex" />
  </QueuePreview>
</template>
