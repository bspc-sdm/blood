<script setup lang="ts">
// ফার্মেসি/প্যাথলজির "আমার কাজ"-এর উপরে (বাঁ ভাগের মাথায়; ডানে "কোড দিয়ে প্রেসক্রিপশন খুলুন" আগের মতো):
// "রোগীরা আপনাকে যেভাবে দেখেন" কার্ড + এক চাপের সুইচ — এখন খোলা / এখন বন্ধ (হাতে, পরের খোলা/বন্ধের সময় পর্যন্ত;
// pharmacy_open_override / lab_open_override), ফার্মেসি: ডেলিভারি চালু/বন্ধ (pharmacy_delivery_set),
// প্যাথলজি: বাসা থেকে নমুনা চালু/বন্ধ (lab_home_collection_set)। backend/docs/LISTINGS.md "Provider side"।
import { computed, ref } from 'vue';
import { Button, ErrorText } from '@/ui/kit';
import { call, errorText } from '@/lib/api';
import { tr } from '@/lib/i18n';
import { useSession } from '@/lib/session';
import { formatTimeHM } from '@/lib/format';
import ProviderPreview from '@/ui/listing/ProviderPreview.vue';
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
const openVal = computed(() => (raw.value?.openNow === true ? 'open' : raw.value?.openNow === false ? 'closed' : null));
const setOpen = (open: boolean | null) =>
  run(`${props.kind}_open_override`, { open }, (r) => ({ openNow: typeof r.openNow === 'boolean' ? r.openNow : open, openOverride: r.openOverride ?? (open == null ? null : { open }) }));
const untilText = computed(() => {
  const u = ov.value?.until;
  if (!u) return LT.overrideNote();
  const d = new Date(u + 6 * 3600_000);
  return tr('Until ', '') + formatTimeHM(d.getUTCHours(), d.getUTCMinutes()) + tr('', ' পর্যন্ত');
});
const extraOn = computed(() => (props.kind === 'pharmacy' ? raw.value?.delivery === true : raw.value?.homeCollection === true));
const setExtra = (on: boolean) =>
  props.kind === 'pharmacy'
    ? run('pharmacy_delivery_set', { delivery: on }, (r) => ({ delivery: typeof r.delivery === 'boolean' ? r.delivery : on }))
    : run('lab_home_collection_set', { homeCollection: on }, (r) => ({ homeCollection: typeof r.homeCollection === 'boolean' ? r.homeCollection : on }));
</script>

<template>
  <ProviderPreview v-if="item && approved" :l="own.listing(item)" :public-to="`/services/${kind}/${item.id}`" :store-key="kind">
    <SwitchPair
      :label="tr('Open now', 'এখন খোলা')"
      testid="sw-open"
      :busy="busy"
      :value="openVal"
      :options="[{ key: 'open', label: LT.openNowSw(), icon: 'storefront', good: true }, { key: 'closed', label: LT.closedNowSw(), icon: 'close' }]"
      @pick="(k) => setOpen(k === 'open')"
    />
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
    <div v-if="ov" class="row wrap gap-6" data-testid="override-note">
      <span class="t-body-s muted">{{ untilText }}</span>
      <Button variant="text" small :disabled="busy" @click="setOpen(null)">{{ LT.followHours() }}</Button>
    </div>
    <ErrorText :text="err" />
  </ProviderPreview>
</template>
