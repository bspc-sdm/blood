<script setup lang="ts">
// বাসায় সেবার উপজেলা: নিজের জেলা আগে, তারপর কাছের জেলাগুলো (সীমানার কাছে থাকলে পাশের জেলায়ও যান)
import { computed, ref } from 'vue';
import { Dialog, ListRow } from '@/ui/kit';
import { isBangla, tr } from '@/lib/i18n';
import { DISTRICTS, bnNorm, distanceKm, districtLabel, upazilasOf } from '@/lib/geo';

const props = defineProps<{ own: string; lat: number | null; lng: number | null }>();
const emit = defineEmits<{ pick: [id: number]; close: [] }>();
const q = ref('');
const items = computed(() => {
  const { lat, lng } = props;
  const near = lat != null && lng != null ? [...DISTRICTS].sort((a, b) => distanceKm(lat, lng, a.lat, a.lng) - distanceKm(lat, lng, b.lat, b.lng)).slice(0, 6).map((d) => d.bn) : [];
  const districts = [...new Set([props.own, ...near].filter(Boolean))];
  return districts.flatMap((d) => upazilasOf(d));
});
const shown = computed(() => {
  const t = q.value.trim().toLowerCase();
  return items.value.filter((u) => !t || bnNorm(u.bn).includes(bnNorm(q.value.trim())) || u.en.toLowerCase().includes(t));
});
const label = (u: { district: string; bn: string; en: string }) => (isBangla() ? u.bn : u.en) + (u.district === props.own ? '' : ` (${districtLabel(u.district)})`);
</script>

<template>
  <Dialog :title="tr('Upazila', 'উপজেলা')" @close="emit('close')">
    <input v-model="q" class="input" :placeholder="tr('Search', 'খুঁজুন')" :aria-label="tr('Search', 'খুঁজুন')" />
    <div class="col gap-2 appt-pick-list">
      <ListRow v-for="u in shown" :key="u.id" :title="label(u)" :on-click="() => emit('pick', u.id)"><template #trailing><span /></template></ListRow>
      <p v-if="!items.length" class="t-body-m muted" :style="{ padding: '16px' }">{{ tr('Nothing found', 'কিছু পাওয়া যায়নি') }}</p>
    </div>
  </Dialog>
</template>
