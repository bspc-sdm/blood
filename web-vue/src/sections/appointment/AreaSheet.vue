<script setup lang="ts">
// এলাকা (অ্যাপের AreaSheet: আমার কাছে · সব জেলা · জেলা › উপজেলা › ইউনিয়ন)
import { computed, ref } from 'vue';
import { Dialog, Icon, ListRow, Loading } from '@/ui/kit';
import { isBangla, tr } from '@/lib/i18n';
import { areaLabel, districtLabel, districtsSorted, searchAreas, unionsOf, upazilasOf, type AreaHit } from '@/lib/geo';
import { useAreasReady, type AreaSel } from './shared';

const props = defineProps<{
  value: AreaSel; allowAll?: boolean; withUnions?: boolean;
  /** null/undefined = "আমার কাছে" দেখানো হয় না */
  nearMe?: boolean | null; onNearMe?: () => void;
}>();
const emit = defineEmits<{ pick: [v: AreaSel]; close: [] }>();
const ready = useAreasReady();
const q = ref('');
const dist = ref<string | null>(null);
const upz = ref<number | null>(null);
const hits = computed<AreaHit[]>(() => (ready.value && q.value.trim() ? searchAreas(q.value, !!props.withUnions) : []));
const sel = (d: string | null, u: number | null, n: number | null) => props.nearMe !== true && props.value.district === d && props.value.upazilaId === u && props.value.unionId === n;
const pick = (d: string | null, u: number | null, n: number | null) => emit('pick', { district: d, upazilaId: u, unionId: n });
const checkStyle = { width: '20px', height: '20px' };
function openDist(d: string | null) {
  dist.value = d;
  upz.value = null;
}
</script>

<template>
  <Dialog :title="tr('Choose area', 'এলাকা বেছে নিন')" @close="emit('close')">
    <input
      v-model="q"
      class="input"
      :placeholder="withUnions ? tr('District, upazila or union', 'জেলা, উপজেলা বা ইউনিয়ন লিখুন') : tr('District or upazila', 'জেলা বা উপজেলা লিখুন')"
      :aria-label="tr('Search area', 'এলাকা খুঁজুন')"
    />
    <div class="col gap-2 appt-pick-list">
      <template v-if="q.trim()">
        <Loading v-if="!ready" />
        <ListRow
          v-for="h in hits"
          :key="`${h.district}-${h.upazilaId}-${h.unionId}`"
          icon="location_on"
          :title="areaLabel(h.district, h.upazilaId, h.unionId)"
          :sub="h.unionId != null ? tr('Union', 'ইউনিয়ন') : h.upazilaId != null ? tr('Upazila', 'উপজেলা') : tr('District', 'জেলা')"
          :on-click="() => pick(h.district, h.upazilaId, h.unionId)"
        >
          <template #trailing><Icon v-if="sel(h.district, h.upazilaId, h.unionId)" name="check_circle" class="c-primary" :style="checkStyle" /><span v-else /></template>
        </ListRow>
        <p v-if="ready && !hits.length" class="t-body-m muted" :style="{ padding: '16px' }">{{ tr('No area found', 'কোনো এলাকা পাওয়া যায়নি') }}</p>
      </template>
      <template v-else-if="!dist">
        <ListRow v-if="nearMe != null && onNearMe" icon="location_on" :title="tr('My current location', 'আমার বর্তমান লোকেশন')" :sub="tr('Nearest first', 'কাছেরগুলো আগে')" :on-click="onNearMe">
          <template #trailing><Icon v-if="nearMe === true" name="check_circle" class="c-primary" :style="checkStyle" /><span v-else /></template>
        </ListRow>
        <ListRow v-if="allowAll" icon="map" :title="tr('All districts', 'সব জেলা')" :on-click="() => pick(null, null, null)">
          <template #trailing><Icon v-if="sel(null, null, null)" name="check_circle" class="c-primary" :style="checkStyle" /><span v-else /></template>
        </ListRow>
        <ListRow v-if="value.district" icon="location_on" :title="areaLabel(value.district, value.upazilaId, value.unionId)" :sub="tr('Selected', 'বাছা আছে')" :on-click="() => openDist(value.district)" />
        <hr class="divider" />
        <template v-for="d in districtsSorted()" :key="d.en">
          <ListRow v-if="value.district === d.bn && nearMe !== true" :title="isBangla() ? d.bn : d.en" :on-click="() => openDist(d.bn)">
            <template #trailing><Icon name="check_circle" class="c-primary" :style="checkStyle" /></template>
          </ListRow>
          <ListRow v-else :title="isBangla() ? d.bn : d.en" :on-click="() => openDist(d.bn)" />
        </template>
      </template>
      <template v-else-if="upz == null">
        <ListRow icon="arrow_back" :title="tr('District', 'জেলা')" :on-click="() => (dist = null)"><template #trailing><span /></template></ListRow>
        <ListRow icon="location_on" :title="tr('Whole district', 'পুরো জেলা') + ' · ' + districtLabel(dist)" :on-click="() => pick(dist, null, null)">
          <template #trailing><Icon v-if="sel(dist, null, null)" name="check_circle" class="c-primary" :style="checkStyle" /><span v-else /></template>
        </ListRow>
        <Loading v-if="!ready" />
        <template v-else>
          <template v-for="u in upazilasOf(dist)" :key="u.id">
            <ListRow v-if="withUnions" :title="isBangla() ? u.bn : u.en" :on-click="() => (upz = u.id)" />
            <ListRow v-else :title="isBangla() ? u.bn : u.en" :on-click="() => pick(dist, u.id, null)">
              <template #trailing><Icon v-if="sel(dist, u.id, null)" name="check_circle" class="c-primary" :style="checkStyle" /><span v-else /></template>
            </ListRow>
          </template>
        </template>
      </template>
      <template v-else>
        <ListRow icon="arrow_back" :title="areaLabel(dist)" :on-click="() => (upz = null)"><template #trailing><span /></template></ListRow>
        <ListRow icon="location_on" :title="tr('All unions', 'সব ইউনিয়ন') + ' · ' + areaLabel(dist, upz)" :on-click="() => pick(dist, upz, null)">
          <template #trailing><Icon v-if="sel(dist, upz, null)" name="check_circle" class="c-primary" :style="checkStyle" /><span v-else /></template>
        </ListRow>
        <ListRow v-for="n in unionsOf(upz)" :key="n.id" :title="isBangla() ? n.bn : n.en" :on-click="() => pick(dist, upz, n.id)">
          <template #trailing><Icon v-if="sel(dist, upz, n.id)" name="check_circle" class="c-primary" :style="checkStyle" /><span v-else /></template>
        </ListRow>
      </template>
    </div>
  </Dialog>
</template>
