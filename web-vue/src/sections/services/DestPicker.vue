<script setup lang="ts">
// গন্তব্য বাছাই: জেলার উপজেলা / অন্য জেলা / হাসপাতাল (লিখুন)।
// district: যে জেলার উপজেলা আগে দেখাবে; hospitals: নাম মেলাতে পরামর্শ (কাছের গাড়ির তালিকা থেকে)।
import { computed, onMounted, ref, watch } from 'vue';
import { Select, TextInput } from '@/ui/kit';
import { tr } from '@/lib/i18n';
import { districtLabel, districtsSorted, ensureAreas, upazilasOf } from '@/lib/geo';
import { fromFacility, keyOf, type Dest } from './fare';
import FacilityDialog from './FacilityDialog.vue';

const props = withDefaults(defineProps<{ label: string; district: string; value: Dest | null; hospitals?: string[]; exclude?: string[]; error?: string | null }>(), {
  hospitals: () => [],
  exclude: () => [],
});
const emit = defineEmits<{ change: [d: Dest | null] }>();
onMounted(() => ensureAreas().catch(() => {}));
const hospText = ref(props.value?.kind === 'hospital' ? props.value.name : '');
const mode = ref(keyOf(props.value));
watch(
  () => (props.value?.kind === 'hospital' ? props.value.name : keyOf(props.value)),
  () => {
    mode.value = keyOf(props.value);
    if (props.value?.kind === 'hospital') hospText.value = props.value.name;
  },
);
const listOpen = ref(false);
const options = computed(() => {
  const ups = upazilasOf(props.district).filter((u) => !props.exclude.includes(`u:${u.id}`));
  const dists = districtsSorted().filter((d) => !props.exclude.includes(`d:${d.bn}`));
  const fac = props.value?.kind === 'hospital' && props.value.facilityId ? props.value : null;
  return [
    ...(fac ? [{ value: keyOf(fac), label: `${fac.name} (${tr('hospital', 'হাসপাতাল')})` }] : []),
    { value: 'f', label: tr('A hospital or clinic — from the list', 'হাসপাতাল বা ক্লিনিক — তালিকা থেকে') },
    ...ups.map((u) => ({ value: `u:${u.id}`, label: `${tr(u.en, u.bn)} (${tr('upazila', 'উপজেলা')})` })),
    { value: 'h', label: tr('Another hospital (type the name)', 'অন্য হাসপাতাল (নাম লিখুন)') },
    ...dists.map((d) => ({ value: `d:${d.bn}`, label: `${districtLabel(d.bn)} (${tr('district', 'জেলা')})` })),
  ];
});
const listId = computed(() => `hosp-${props.district}`);
function pick(v: string) {
  mode.value = v;
  if (v === 'f') {
    listOpen.value = true;
    return;
  }
  if (!v) emit('change', null);
  else if (v.startsWith('f:')) return;
  else if (v === 'h') emit('change', hospText.value.trim().length >= 3 ? { kind: 'hospital', name: hospText.value.trim() } : null);
  else if (v.startsWith('u:')) emit('change', { kind: 'upazila', upazilaId: Number(v.slice(2)) });
  else emit('change', { kind: 'district', district: v.slice(2) });
}
function onHosp(v: string) {
  hospText.value = v;
  const t = v.trim();
  emit('change', t.length >= 3 ? { kind: 'hospital', name: t } : null);
}
function onFac(f: { facilityId: string; name: string; upazilaId: number | null; district: string }) {
  listOpen.value = false;
  if (props.exclude.includes(`f:${f.facilityId}`)) return;
  emit('change', fromFacility(f));
}
</script>

<template>
  <div class="col gap-6">
    <Select :label="label" :model-value="mode" :placeholder="tr('Choose…', 'বেছে নিন…')" :options="options" :error="mode === 'h' ? undefined : error ?? undefined" @update:model-value="pick" />
    <template v-if="mode === 'h'">
      <TextInput :label="tr('Hospital name', 'হাসপাতালের নাম')" :model-value="hospText" maxlength="80" :list="hospitals.length ? listId : undefined" :error="error ?? undefined" @update:model-value="onHosp" />
      <datalist v-if="hospitals.length" :id="listId"><option v-for="h in hospitals" :key="h" :value="h" /></datalist>
    </template>
    <FacilityDialog v-if="listOpen" :title="tr('Choose hospital or clinic', 'হাসপাতাল বা ক্লিনিক বাছুন')" :district="district" @close="listOpen = false" @pick="onFac" />
  </div>
</template>
