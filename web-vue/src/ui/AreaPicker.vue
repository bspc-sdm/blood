<script setup lang="ts">
// AreaPicker — জেলা › উপজেলা › (ইউনিয়ন), সেবা/ডাক্তারের তালিকার জন্য (অ্যাপের AreaChips + খোঁজা)
import { computed, onMounted, ref } from 'vue';
import { tr, isBangla } from '@/lib/i18n';
import { areaLabel, areasReady, currentPosition, districtAt, districtsSorted, ensureAreas, searchAreas, unionsOf, upazilasOf, type AreaHit } from '@/lib/geo';
import type { AreaValue } from './areaFilter';
import Dialog from './Dialog.vue';
import Button from './Button.vue';
import ErrorText from './ErrorText.vue';
import ListRow from './ListRow.vue';

const props = withDefaults(defineProps<{ value: AreaValue | null; withUnions?: boolean; title?: string; allowAllDistrict?: boolean }>(), { allowAllDistrict: true });
const emit = defineEmits<{ picked: [v: AreaValue]; close: [] }>();
const ready = ref(areasReady());
const err = ref<string | null>(null);
const q = ref('');
const dist = ref<string | null>(props.value?.district ?? null);
// ইউনিয়ন না চাইলে উপজেলার ধাপ থেকেই শুরু (বাছা উপজেলা থাকলেও)
const upz = ref<number | null>(props.withUnions ? props.value?.upazilaId ?? null : null);
const gps = ref(false);

onMounted(() => {
  ensureAreas().then(() => (ready.value = true), () => (err.value = tr("Couldn't load areas. Check your internet.", 'এলাকার তালিকা আনা যায়নি। ইন্টারনেট দেখুন।')));
});

const hits = computed<AreaHit[]>(() => (ready.value && q.value.trim() ? searchAreas(q.value, !!props.withUnions) : []));

async function useGps() {
  gps.value = true;
  err.value = null;
  try {
    const { lat, lng } = await currentPosition();
    const d = await districtAt(lat, lng);
    dist.value = d.bn;
    upz.value = null;
  } catch {
    err.value = tr("Couldn't get your location.", 'লোকেশন পাওয়া যায়নি।');
  } finally {
    gps.value = false;
  }
}

const step = computed(() => (!dist.value ? 'district' : upz.value == null ? 'upazila' : 'union'));
const back = () => (step.value === 'union' ? (upz.value = null) : step.value === 'upazila' ? (dist.value = null) : emit('close'));
function pickUpazila(id: number) {
  if (props.withUnions) upz.value = id;
  else emit('picked', { district: dist.value!, upazilaId: id, unionId: null });
}
</script>

<template>
  <Dialog :title="title ?? tr('Choose area', 'এলাকা বেছে নিন')" @close="emit('close')">
    <input
      v-model="q"
      class="input"
      :placeholder="withUnions ? tr('Search district, upazila or union', 'জেলা, উপজেলা বা ইউনিয়ন খুঁজুন') : tr('Search district or upazila', 'জেলা বা উপজেলা খুঁজুন')"
      :aria-label="tr('Search area', 'এলাকা খুঁজুন')"
    />
    <ErrorText :text="err" />
    <div v-if="q.trim()" class="col gap-2" :style="{ maxHeight: '50dvh', overflowY: 'auto', margin: '0 -8px' }">
      <div v-if="!ready" class="loading"><span class="spinner" /></div>
      <ListRow v-for="h in hits" :key="`${h.district}-${h.upazilaId}-${h.unionId}`" :title="areaLabel(h.district, h.upazilaId, h.unionId)" @click="emit('picked', h)">
        <template #trailing><span /></template>
      </ListRow>
      <p v-if="ready && !hits.length" class="t-body-m muted" :style="{ padding: '16px' }">{{ tr('No match', 'মেলেনি') }}</p>
    </div>
    <template v-else>
      <div class="row">
        <Button v-if="step !== 'district'" variant="text" icon="arrow_back" @click="back">{{ tr('Back', 'পেছনে') }}</Button>
        <span class="grow t-title-s">{{ step === 'district' ? tr('District', 'জেলা') : step === 'upazila' ? areaLabel(dist) : areaLabel(dist, upz) }}</span>
        <Button v-if="step === 'district'" variant="text" icon="location_on" :busy="gps" @click="useGps">{{ tr('My location', 'আমার লোকেশন') }}</Button>
      </div>
      <div class="col gap-2" :style="{ maxHeight: '50dvh', overflowY: 'auto', margin: '0 -8px' }">
        <template v-if="step === 'district'">
          <ListRow v-for="d in districtsSorted()" :key="d.en" :title="isBangla() ? d.bn : d.en" @click="() => { dist = d.bn; upz = null; }" />
        </template>
        <template v-if="step === 'upazila'">
          <ListRow v-if="allowAllDistrict" icon="check_circle" :title="tr(`All of ${areaLabel(dist)}`, `পুরো ${areaLabel(dist)}`)" @click="emit('picked', { district: dist!, upazilaId: null, unionId: null })">
            <template #trailing><span /></template>
          </ListRow>
          <div v-if="!ready" class="loading"><span class="spinner" /></div>
          <template v-else>
            <ListRow v-for="u in upazilasOf(dist!)" :key="u.id" :title="isBangla() ? u.bn : u.en" @click="pickUpazila(u.id)">
              <template v-if="!withUnions" #trailing><span /></template>
            </ListRow>
          </template>
        </template>
        <template v-if="step === 'union'">
          <ListRow icon="check_circle" :title="tr(`All of ${areaLabel(dist, upz)}`, `পুরো ${areaLabel(dist, upz)}`)" @click="emit('picked', { district: dist!, upazilaId: upz, unionId: null })">
            <template #trailing><span /></template>
          </ListRow>
          <ListRow v-for="n in unionsOf(upz!)" :key="n.id" :title="isBangla() ? n.bn : n.en" @click="emit('picked', { district: dist!, upazilaId: upz, unionId: n.id })">
            <template #trailing><span /></template>
          </ListRow>
        </template>
      </div>
    </template>
  </Dialog>
</template>
