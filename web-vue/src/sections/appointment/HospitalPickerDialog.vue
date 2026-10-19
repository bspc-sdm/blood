<script setup lang="ts">
// হাসপাতাল বাছাই: জেলা/উপজেলা, নাম লিখে খোঁজা, না থাকলে যোগের অনুরোধ
import { computed, ref } from 'vue';
import { Button, Dialog, ErrorText, Icon, InfoText, ListRow, Loading } from '@/ui/kit';
import AreaPicker from '@/ui/AreaPicker.vue';
import type { AreaValue } from '@/ui/areaFilter';
import { errorText } from '@/lib/api';
import { useCall } from '@/lib/query';
import { useSession } from '@/lib/session';
import { tr } from '@/lib/i18n';
import { areaLabel } from '@/lib/geo';
import { hospitalLabel, hospitalTypeLabel, similarHospitals, type Hospital } from './model';
import { useAreasReady } from './shared';
import AddHospitalDialog from './AddHospitalDialog.vue';

const props = defineProps<{ initialDistrict: string | null; initialUpazilaId?: number | null }>();
const emit = defineEmits<{ pick: [h: Hospital]; close: [] }>();
const s = useSession();
useAreasReady();
const district = ref<string | null>(props.initialDistrict);
const upazilaId = ref<number | null>(props.initialUpazilaId ?? null);
const q = ref('');
const area = ref(false);
const adding = ref(false);
const info = ref<string | null>(null);
const hq = useCall<{ items: Hospital[] }>('hospitals', () => ({ district: district.value }), () => ({ enabled: !!district.value }));
const shown = computed(() => {
  const list = hq.data?.items ?? [];
  const inArea = list.filter((h) => upazilaId.value == null || h.upazilaId === upazilaId.value || h.upazilaId == null);
  const t = q.value.trim();
  if (!t) return inArea;
  const direct = inArea.filter((h) => h.nameEn.toLowerCase().includes(t.toLowerCase()) || h.nameBn.includes(t));
  return [...new Set([...direct, ...similarHospitals(t, inArea, 10)])];
});
function areaPicked(v: AreaValue) {
  district.value = v.district;
  upazilaId.value = v.upazilaId;
  area.value = false;
}
function existing(h: Hospital) {
  adding.value = false;
  emit('pick', h);
}
function sent(m: string) {
  adding.value = false;
  info.value = m;
}
</script>

<template>
  <Dialog :title="tr('Choose hospital', 'হাসপাতাল বেছে নিন')" @close="emit('close')">
    <button type="button" class="area-btn" @click="area = true">
      <Icon name="location_on" />
      <span>{{ district ? areaLabel(district, upazilaId) : tr('District', 'জেলা') }}</span>
      <Icon name="expand_more_round" />
    </button>
    <input v-model="q" class="input" :placeholder="tr('Hospital / clinic name', 'হাসপাতাল / ক্লিনিকের নাম')" :aria-label="tr('Hospital / clinic name', 'হাসপাতাল / ক্লিনিকের নাম')" />
    <div v-if="district" class="row">
      <span class="t-body-s muted">{{ tr('Not in the list?', 'তালিকায় নেই?') }}</span>
      <Button variant="text" small @click="s.requireLogin(tr('Log in to request a hospital', 'হাসপাতাল যোগের অনুরোধ করতে লগইন করুন'), () => (adding = true))">
        {{ tr('Request to add', 'যোগ করার অনুরোধ') }}
      </Button>
    </div>
    <InfoText :text="info" />
    <ErrorText :text="hq.error ? errorText(hq.error) : null" />
    <div class="col gap-2 appt-pick-list">
      <p v-if="!district" class="t-body-m" :style="{ padding: '12px' }">{{ tr('Choose the district first', 'আগে জেলা বেছে নিন') }}</p>
      <Loading v-else-if="hq.isLoading" />
      <template v-else>
        <ListRow
          v-for="h in shown.slice(0, 300)"
          :key="h.id"
          icon="local_hospital"
          :title="hospitalLabel(h)"
          :sub="[hospitalTypeLabel(h.type), areaLabel(null, h.upazilaId)].filter(Boolean).join(' · ')"
          :on-click="() => emit('pick', h)"
        >
          <template #trailing><span /></template>
        </ListRow>
      </template>
    </div>
  </Dialog>
  <AreaPicker v-if="area" :value="district ? { district, upazilaId } : null" @picked="areaPicked" @close="area = false" />
  <AddHospitalDialog
    v-if="adding"
    :initial-name="q"
    :initial-district="district"
    :initial-upazila-id="upazilaId"
    @existing="existing"
    @sent="sent"
    @close="adding = false"
  />
</template>
