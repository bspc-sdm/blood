<script setup lang="ts">
// ডাক্তারের "আমার চেম্বার" (আর চেম্বারের ডেস্কে সহকারী): "রোগীরা আপনাকে যেভাবে দেখেন" — রোগীদের তালিকার একই কার্ড
// (chamber_days.card = providers-এর হুবহু উত্তর: nextSlot, feeAmount, রেটিং …), আর আজ যে চেম্বারগুলো বসে তার প্রতিটায়
// এক চাপে "আজ সিরিয়াল চলছে / আজ বন্ধ" (chamber_day_set — বন্ধ দিনে নতুন বুকিং নয়, nextSlot পরের দিনে যায়)।
// সহকারী: শুধু যে চেম্বারের অনুমতি আছে (সার্ভার সেগুলোই দেয়)। পুরোনো সার্ভারে chamber_days নেই → provider_me-র কার্ড, সুইচ নেই।
import { computed, ref } from 'vue';
import { ErrorText, InfoText } from '@/ui/kit';
import { call, errorText } from '@/lib/api';
import { useCall } from '@/lib/query';
import { isBangla, num, tr } from '@/lib/i18n';
import ProviderPreview from '@/ui/listing/ProviderPreview.vue';
import SwitchPair from '@/ui/listing/SwitchPair.vue';
import { LT } from '@/ui/listing/model';
import { parseProvider } from '../model';
import { doctorListing } from '../listing';

interface DayItem { chamberIndex: number; chamberKey?: string; name: string; nameBn?: string; online?: boolean; appointments?: boolean; sitsToday?: boolean; closedToday?: boolean; requestsToday?: number }
const props = defineProps<{ doctorUid?: string | null; fallbackCard?: any | null }>();
const days = useCall<{ items?: DayItem[]; card?: any | null; role?: string }>('chamber_days', () => (props.doctorUid ? { doctorUid: props.doctorUid } : {}), { retry: false, staleTime: 0 });
const card = computed(() => days.data?.card ?? props.fallbackCard ?? null);
const listing = computed(() => (card.value ? doctorListing(parseProvider(card.value), card.value) : null));
const today = computed(() => (days.data?.items ?? []).filter((d) => d.sitsToday && d.appointments !== false));
const busy = ref<number | null>(null);
const err = ref<string | null>(null);
const info = ref<string | null>(null);
async function set(d: DayItem, closed: boolean) {
  busy.value = d.chamberIndex;
  err.value = null;
  info.value = null;
  try {
    const r = await call<{ requests?: number }>('chamber_day_set', { ...(props.doctorUid ? { doctorUid: props.doctorUid } : {}), chamberIndex: d.chamberIndex, ...(d.chamberKey ? { chamberKey: d.chamberKey } : {}), closed });
    if (closed && (r?.requests ?? 0) > 0) info.value = tr(`${r.requests} requests are already made for today — let them know.`, `আজকের জন্য আগেই ${num(r.requests!)}টা অনুরোধ আছে — তাঁদের জানিয়ে দিন।`); // bn-ok
    await days.refetch();
  } catch (e) {
    err.value = errorText(e);
  } finally {
    busy.value = null;
  }
}
const chName = (d: DayItem) => (isBangla() && d.nameBn ? d.nameBn : d.name);
</script>

<template>
  <ProviderPreview :l="listing" :loading="days.isLoading" :public-to="card?.uid ? `/services/doctor/${card.uid}` : null" :store-key="'doctor.' + (doctorUid ?? 'me')">
    <template v-if="today.length">
      <div v-for="d in today" :key="d.chamberIndex" class="col gap-4" :style="{ flex: '1 1 100%' }">
        <span class="t-label-l muted">{{ chName(d) }}</span>
        <SwitchPair
          :label="chName(d)"
          :testid="`sw-chamber-${d.chamberIndex}`"
          :busy="busy === d.chamberIndex"
          :value="d.closedToday ? 'off' : 'on'"
          :options="[{ key: 'on', label: LT.serialOn(), icon: 'event_outline', good: true }, { key: 'off', label: LT.serialOff(), icon: 'close' }]"
          @pick="(k) => set(d, k === 'off')"
        />
      </div>
    </template>
    <InfoText v-if="info" :text="info" />
    <ErrorText v-if="err" :text="err" />
  </ProviderPreview>
</template>
