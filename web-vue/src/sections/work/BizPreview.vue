<script setup lang="ts">
// ফার্মেসি/প্যাথলজির কাজের সারির নিচে: "রোগীরা আপনাকে যেভাবে দেখেন" এক লাইনে ("দেখুন" → পুরো কার্ড), এখনকার অবস্থা
// (খোলা / বিরতি / বন্ধ — শুধু দেখায়, "বদলান" → /work; বদলানো এক জায়গায়, আমার কাজ CONTRACT §১.২) আর এক চাপের সুইচ —
// ফার্মেসি: ডেলিভারি চালু/বন্ধ (pharmacy_delivery_set), প্যাথলজি: বাসা থেকে নমুনা চালু/বন্ধ (lab_home_collection_set)।
// backend/docs/LISTINGS.md "Provider side"।
import { computed, ref } from 'vue';
import { ErrorText } from '@/ui/kit';
import { call, errorText } from '@/lib/api';
import { tr } from '@/lib/i18n';
import { useSession } from '@/lib/session';
import { formatTime } from '@/lib/format';
import QueuePreview from './QueuePreview.vue';
import StateLine from './StateLine.vue';
import { TT } from './today/text';
import SwitchPair from '@/ui/listing/SwitchPair.vue';
import { LT } from '@/ui/listing/model';
import { useOwnListings, type OwnItem } from './ownListing';

const props = defineProps<{ kind: 'pharmacy' | 'lab' }>();
const s = useSession();
const own = useOwnListings(props.kind);
const item = computed(() => ((props.kind === 'pharmacy' ? s.work.pharmacy : s.work.lab) ?? null) as (OwnItem & Record<string, any>) | null);
const raw = computed(() => (item.value ? own.raw(item.value) : null));
const approved = computed(() => item.value?.status === 'approved');
const busy = ref(false);
const err = ref<string | null>(null);
async function run(action: string, body: Record<string, unknown>, patch: (r: any) => Record<string, unknown>) {
  if (!item.value) return;
  busy.value = true;
  err.value = null;
  try {
    const r = await call<any>(action, body);
    own.patch(item.value, patch(r ?? {}));
  } catch (e) {
    err.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
const ov = computed(() => (raw.value?.openOverride && typeof raw.value.openOverride === 'object' ? (raw.value.openOverride as { open: boolean; until?: number }) : null));
/** খোলা (সবুজ) · বিরতি — "আবার খুলবে hh:mm" (কমলা) · বন্ধ (লাল); জানা না থাকলে কিছু নয় */
const state = computed<{ label: string; tone: 'good' | 'warn' | 'error' } | null>(() => {
  const r = raw.value;
  if (r?.openNow === true) return { label: TT.open(), tone: 'good' };
  if (r?.openNow !== false) return null;
  const u = ov.value && ov.value.open === false ? ov.value.until : undefined;
  if (u && u > Date.now()) return { label: TT.reopensAt(formatTime(u)), tone: 'warn' };
  return { label: TT.closed(), tone: 'error' };
});
const extraOn = computed(() => (props.kind === 'pharmacy' ? raw.value?.delivery === true : raw.value?.homeCollection === true));
const setExtra = (on: boolean) =>
  props.kind === 'pharmacy'
    ? run('pharmacy_delivery_set', { delivery: on }, (r) => ({ delivery: typeof r.delivery === 'boolean' ? r.delivery : on }))
    : run('lab_home_collection_set', { homeCollection: on }, (r) => ({ homeCollection: typeof r.homeCollection === 'boolean' ? r.homeCollection : on }));
</script>

<template>
  <QueuePreview v-if="item && approved" :l="own.listing(item)" :public-to="`/services/${kind}/${item.id}`">
    <StateLine v-if="state" :label="state.label" :tone="state.tone" />
    <SwitchPair
      :label="kind === 'pharmacy' ? tr('Delivery', 'ডেলিভারি') : LT.homeSample()"
      :testid="kind === 'pharmacy' ? 'sw-delivery' : 'sw-home'"
      :busy="busy"
      :value="extraOn ? 'on' : 'off'"
      :options="kind === 'pharmacy'
        ? [{ key: 'on', label: LT.deliveryOn(), icon: 'local_shipping', good: true }, { key: 'off', label: LT.deliveryOff() }]
        : [{ key: 'on', label: LT.homeOn(), icon: 'home_health', good: true }, { key: 'off', label: LT.homeOff() }]"
      @pick="(k) => setExtra(k === 'on')"
    />
    <ErrorText :text="err" />
  </QueuePreview>
</template>
