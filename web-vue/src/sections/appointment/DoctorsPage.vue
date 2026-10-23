<script setup lang="ts">
// অ্যাপয়েন্টমেন্ট: ডাক্তার / পুষ্টিবিদ / ফিটনেস ট্রেইনারের তালিকা আর বিস্তারিত (অ্যাপের AppointmentTab, ProviderList,
// ProviderCard, ProviderDetailDialog, BookDialog)। বাছা মানুষ URL-এ: /appointment/doctor/:id?t=doctor
import { computed, reactive, ref, watch } from 'vue';
import { RouterLink } from 'vue-router';
import { Button, ChoiceChips, EmptyState, ErrorBox, FilterBar, Icon, InfoText, PillTabs, SkeletonCards, Switch } from '@/ui/kit';
import { useFilterArea } from '@/ui/areaFilter';
import DetailPane from '@/shell/DetailPane.vue';
import ListDetail from '@/shell/ListDetail.vue';
import { usePageTitle } from '@/shell/chrome';
import { errorText } from '@/lib/api';
import { useCall } from '@/lib/query';
import { useSession } from '@/lib/session';
import { store } from '@/lib/store';
import { tr } from '@/lib/i18n';
import { areaLabel } from '@/lib/geo';
import { useNav, useSearchParams } from '@/lib/nav';
import { CHAMBER_TYPES, FEE_STEPS, badgeIcon, chamberLabel, parseProvider, specialtiesOf, specialtyLabel, useCatalog, type Hospital, type SpecialtyFacet } from './model';
import { NO_FILTERS, TAB_ICON, dayLabel, feeLabel, filterKey, genderLabel, loadFilters, tabLabel, useDebounced, type SavedFilters } from './doctors';
import { useAreasReady, type AreaSel } from './shared';
import AreaSheet from './AreaSheet.vue';
import FilterSheet from './FilterSheet.vue';
import HospitalPickerDialog from './HospitalPickerDialog.vue';
import SpecialtyPicker from './SpecialtyPicker.vue';
import ProviderCard from './ProviderCard.vue';
import ProviderDetail from './ProviderDetail.vue';
import { T as OT } from './online';

const props = defineProps<{ detailId: string | null }>();
usePageTitle(() => tr('Appointment', 'অ্যাপয়েন্টমেন্ট'));
useCatalog();
useAreasReady();
const nav = useNav();
const [sp, setSp] = useSearchParams();
const s = useSession();
const type = computed(() => (CHAMBER_TYPES.includes(sp.value.get('t') ?? '') ? (sp.value.get('t') as string) : 'doctor'));
const [fa, setFa] = useFilterArea();
const area = ref<AreaSel | null>(null);
const cur = computed<AreaSel>(() => area.value ?? { district: fa.value?.district ?? null, upazilaId: fa.value?.upazilaId ?? null, unionId: fa.value?.unionId ?? null });
// ২.৩৩.০: আজ/আগামীকাল, এখন চেম্বারে, বিশেষজ্ঞতা, ফি, হাসপাতাল, লিঙ্গ — শেষবারেরটা মনে থাকে (অ্যাপেও)
const saved = reactive<Record<string, SavedFilters>>({});
const f = computed(() => saved[type.value] ?? loadFilters(type.value));
const setF = (patch: Partial<SavedFilters>) => {
  const n = { ...f.value, ...patch };
  store.setJson(filterKey(type.value), n);
  saved[type.value] = n;
};
const clearF = () => setF(NO_FILTERS);
const hq = useDebounced(() => f.value.hospitalQ.trim(), 400);
const query = ref('');
const showArea = ref(false);
const showFilter = ref(false);
const pickHospital = ref(false);
const info = ref<string | null>(null);

const body = computed(() => {
  const ff = f.value;
  const c = cur.value;
  const b: Record<string, unknown> = { type: type.value, homeVisit: ff.homeOnly };
  // অনলাইন (ভিডিও): ঠিকানা নেই, তাই এলাকা/হাসপাতাল ছাড়া সব জায়গার
  if (ff.online) b.online = true;
  else if (ff.hospital) b.hospitalId = ff.hospital.id;
  else {
    if (c.district) b.district = c.district;
    if (c.upazilaId != null) b.upazilaId = c.upazilaId;
    if (c.unionId != null) b.unionId = c.unionId;
  }
  if (ff.specialty) b.specialty = ff.specialty;
  if (ff.day) b.day = ff.day;
  if (ff.openNow) b.openNow = true;
  if (ff.maxFee) b.maxFee = ff.maxFee;
  if (hq.value.length >= 2) b.hospitalQ = hq.value;
  if (ff.gender) b.gender = ff.gender;
  return b;
});
type Facets = { specialties: SpecialtyFacet[]; gender: boolean };
const q = useCall<{ items: any[]; facets?: Facets }>('providers', body);
const list = computed(() => (q.data?.items ?? []).map((o) => ({ p: parseProvider(o), matched: Array.isArray(o?.matched) ? (o.matched as number[]) : null })));
// বিশেষজ্ঞতার চিপ: সার্ভার যা বলে (এই এলাকায় যেগুলো আছে); নতুন তালিকা আসার সময় আগেরটা থাকে
const lastFacets = reactive<Record<string, Facets>>({});
watch(
  () => q.data?.facets,
  (fc) => {
    if (fc) lastFacets[type.value] = fc;
  },
  { immediate: true },
);
const facets = computed(() => q.data?.facets ?? lastFacets[type.value] ?? null);
const shown = computed(() => {
  const t = query.value.trim().toLowerCase();
  return list.value
    .filter(({ p }) => p.uid !== s.uid)
    .filter(({ p }) => !t || p.name.toLowerCase().includes(t) || p.degrees.toLowerCase().includes(t) || p.chambers.some((c) => chamberLabel(c).toLowerCase().includes(t)));
});

// সরাসরি খোলা লিংক: এখনকার তালিকায় না থাকলে পুরো দেশের তালিকা থেকে
const inList = computed(() => (props.detailId ? list.value.find(({ p }) => p.uid === props.detailId)?.p ?? null : null));
const fb = useCall<{ items: any[] }>('providers', () => ({ type: type.value, homeVisit: false }), () => ({ enabled: !!props.detailId && !inList.value && !q.isLoading }));
const selected = computed(() => inList.value ?? (props.detailId ? (fb.data?.items ?? []).map(parseProvider).find((p) => p.uid === props.detailId) ?? null : null));

const qs = computed(() => sp.value.toString());
const listPath = computed(() => '/appointment' + (qs.value ? `?${qs.value}` : ''));
const open = (id: string) => nav(`/appointment/doctor/${id}${qs.value ? `?${qs.value}` : ''}`);
const hospitalName = (h: Hospital) => chamberLabel({ hospitalName: h.nameEn, hospitalNameBn: h.nameBn });
const active = computed(() => {
  const ff = f.value;
  return [
    ...(ff.day ? [{ label: dayLabel(ff.day), onRemove: () => setF({ day: null }) }] : []),
    ...(ff.openNow ? [{ label: tr('In chamber now', 'এখন চেম্বারে'), onRemove: () => setF({ openNow: false }) }] : []),
    ...(ff.specialty ? [{ label: specialtyLabel(ff.specialty), onRemove: () => setF({ specialty: null }) }] : []),
    ...(ff.maxFee ? [{ label: feeLabel(ff.maxFee), onRemove: () => setF({ maxFee: null }) }] : []),
    ...(ff.hospital ? [{ label: hospitalName(ff.hospital), onRemove: () => setF({ hospital: null }) }] : []),
    ...(ff.hospitalQ.trim() ? [{ label: tr('Hospital: ', 'হাসপাতাল: ') + ff.hospitalQ.trim(), onRemove: () => setF({ hospitalQ: '' }) }] : []),
    ...(ff.gender ? [{ label: genderLabel(ff.gender), onRemove: () => setF({ gender: null }) }] : []),
    ...(ff.homeOnly ? [{ label: tr('Home visit', 'বাসায় যান'), onRemove: () => setF({ homeOnly: false }) }] : []),
    ...(ff.online ? [{ label: OT.online(), onRemove: () => setF({ online: false }) }] : []),
  ];
});
// এক চাপের ছাঁকনি (চালু না থাকলে): আজ · আগামীকাল · এখন চেম্বারে · ৳৫০০ পর্যন্ত · বেশি থাকা ৩টা বিশেষজ্ঞতা
const quick = computed(() => {
  const ff = f.value;
  return [
    ...(!ff.day ? [{ key: 'today', label: dayLabel('today'), on: () => setF({ day: 'today' }) }, { key: 'tomorrow', label: dayLabel('tomorrow'), on: () => setF({ day: 'tomorrow' }) }] : []),
    ...(!ff.online && type.value === 'doctor' ? [{ key: 'online', label: OT.online(), on: () => setF({ online: true }) }] : []),
    ...(!ff.openNow ? [{ key: 'now', label: tr('In chamber now', 'এখন চেম্বারে'), on: () => setF({ openNow: true }) }] : []),
    ...(!ff.maxFee ? [{ key: 'fee', label: feeLabel(500), on: () => setF({ maxFee: 500 }) }] : []),
    ...(!ff.specialty && facets.value ? facets.value.specialties.slice(0, 3).map((x) => ({ key: 'sp-' + x.code, label: specialtyLabel(x.code), on: () => setF({ specialty: x.code }) })) : []),
  ];
});
const isEmptyFilter = computed(() => !cur.value.district && !active.value.length);
const barLabel = computed(() => {
  const h = f.value.hospital;
  const c = cur.value;
  if (f.value.online) return tr('All districts', 'সব জেলা');
  return h ? areaLabel(h.district, h.upazilaId) : c.district ? areaLabel(c.district, c.upazilaId, c.unionId) : tr('All districts', 'সব জেলা');
});
const spOptions = computed(() => (facets.value ? facets.value.specialties.map((x) => x.code) : specialtiesOf(type.value)));
const spCount = (code: string) => facets.value?.specialties.find((x) => x.code === code)?.n;
const emptyTitle = computed(() =>
  type.value === 'nutritionist' ? tr('No nutritionists found', 'কোনো পুষ্টিবিদ পাওয়া যায়নি') : type.value === 'trainer' ? tr('No fitness trainers found', 'কোনো ফিটনেস ট্রেইনার পাওয়া যায়নি') : tr('No doctors found', 'কোনো ডাক্তার পাওয়া যায়নি'),
);

function changeTab(k: string) {
  // ধরনপ্রতি নিজের শেষ ছাঁকনি (store-এ) — বদলালে সেটা ফিরে আসে
  setSp(k === 'doctor' ? {} : { t: k }, { replace: true });
  if (props.detailId) nav(k === 'doctor' ? '/appointment' : `/appointment?t=${k}`, { replace: true });
}
function searchEverywhere() {
  area.value = { district: null, upazilaId: null, unionId: null };
  clearF();
}
function areaPicked(v: AreaSel) {
  // এলাকা বদলালে আগের হাসপাতাল আর মেলে না
  setF({ hospital: null });
  area.value = v;
  if (v.district) setFa({ district: v.district, upazilaId: v.upazilaId, unionId: v.unionId });
  showArea.value = false;
}
function hospitalPicked(h: Hospital) {
  setF({ hospital: h });
  area.value = { district: h.district, upazilaId: null, unionId: null };
  pickHospital.value = false;
}
</script>

<template>
  <ListDetail :has-detail="!!detailId">
    <div class="list-inner appt-pre">
      <div class="row appt-title">
        <h1 class="t-headline grow appt-h1">{{ tr('Appointment', 'অ্যাপয়েন্টমেন্ট') }}</h1>
        <div class="row appt-title-end">
          <RouterLink v-if="!s.guest" class="btn text small has-icon appt-my-link" to="/my-appointments" :aria-label="tr('My appointments', 'আমার অ্যাপয়েন্টমেন্ট')" :title="tr('My appointments', 'আমার অ্যাপয়েন্টমেন্ট')">
            <Icon name="event_outline" />
            <span class="appt-wide-only">{{ tr('My appointments', 'আমার অ্যাপয়েন্টমেন্ট') }}</span>
          </RouterLink>
          <RouterLink class="appt-home-btn" to="/appointment/home-care"><Icon name="home_health" />{{ tr('Home care', 'বাসায় সেবা') }}</RouterLink>
        </div>
      </div>
      <div class="appt-bleed">
        <PillTabs :tabs="CHAMBER_TYPES.map((k) => ({ key: k, label: tabLabel(k), icon: TAB_ICON[k] }))" :value="type" @change="changeTab" />
      </div>
      <FilterBar
        :area-label="barLabel"
        :on-area="() => (showArea = true)"
        :filter-count="active.length"
        :on-filter="() => (showFilter = true)"
        :search="query"
        :on-search="(v: string) => (query = v)"
      >
        <div v-if="active.length || quick.length" class="chips scroll appt-qchips" role="group" :aria-label="tr('Filters', 'ছাঁকনি')">
          <button v-for="a in active" :key="'a-' + a.label" type="button" class="chip on" :aria-label="tr('Remove', 'সরান') + ': ' + a.label" @click="a.onRemove()">{{ a.label }}<Icon name="close" /></button>
          <button v-for="x in quick" :key="x.key" type="button" class="chip" @click="x.on()">{{ x.label }}</button>
        </div>
      </FilterBar>
      <InfoText :text="info" />
      <SkeletonCards v-if="q.isLoading" :n="4" :h="150" />
      <ErrorBox v-else-if="q.error" :error="errorText(q.error)" :on-retry="() => q.refetch()" />
      <EmptyState v-else-if="!shown.length" :icon="badgeIcon(type)" :title="emptyTitle" :text="tr('Try a wider area or remove a filter.', 'বড় এলাকা দিয়ে বা কোনো ফিল্টার সরিয়ে দেখুন।')">
        <Button v-if="active.length" variant="text" @click="clearF">{{ tr('Remove all filters', 'সব ছাঁকনি সরান') }}</Button>
        <Button v-if="!isEmptyFilter && !active.length" variant="text" @click="searchEverywhere">{{ tr('Search everywhere', 'সব জায়গায় খুঁজুন') }}</Button>
      </EmptyState>
      <template v-else>
        <ProviderCard v-for="{ p, matched } in shown" :key="p.uid" :p="p" :matched="matched" :area="cur" :online="f.online" :hospital="f.hospital" :selected="p.uid === detailId" :on-click="() => open(p.uid)" />
      </template>
    </div>
    <template #detail>
      <DetailPane v-if="selected" :title="selected.name" :back-to="listPath" hide-title-wide>
        <ProviderDetail :key="selected.uid" :p="selected" :on-close="() => nav(listPath)" :on-booked="(m: string) => (info = m)" />
      </DetailPane>
      <DetailPane v-else :title="tr('Doctor', 'ডাক্তার')" :back-to="listPath">
        <SkeletonCards v-if="fb.isLoading || q.isLoading" :n="2" :h="160" />
        <EmptyState v-else icon="stethoscope" :title="tr('Not found', 'পাওয়া যায়নি')" />
      </DetailPane>
    </template>
    <template #placeholder><EmptyState :icon="badgeIcon(type)" :title="tabLabel(type)" /></template>
  </ListDetail>
  <AreaSheet
    v-if="showArea"
    :value="f.hospital ? { district: f.hospital.district, upazilaId: null, unionId: null } : cur"
    allow-all
    with-unions
    @pick="areaPicked"
    @close="showArea = false"
  />
  <FilterSheet v-if="showFilter" :title="tr('Filter', 'ফিল্টার')" :on-clear="active.length ? clearF : undefined" @close="showFilter = false">
    <div class="col gap-6">
      <span class="t-title-s">{{ tr('Day', 'দিন') }}</span>
      <ChoiceChips
        :options="[{ value: '', label: tr('Any day', 'যেকোনো দিন') }, { value: 'today', label: dayLabel('today') }, { value: 'tomorrow', label: dayLabel('tomorrow') }]"
        :model-value="f.day ?? ''"
        @update:model-value="(v: string) => setF({ day: v === 'today' || v === 'tomorrow' ? v : null })"
      />
    </div>
    <Switch :label="tr('In chamber now', 'এখন চেম্বারে')" :sub="tr('Sitting at the chamber right now', 'এই মুহূর্তে চেম্বারে বসে আছেন')" :model-value="f.openNow" @update:model-value="(v) => setF({ openNow: v })" />
    <SpecialtyPicker :options="spOptions" :count="spCount" :value="f.specialty" @change="(v) => setF({ specialty: v })" />
    <div class="col gap-6">
      <span class="t-title-s">{{ tr('Hospital / chamber', 'হাসপাতাল / চেম্বার') }}</span>
      <input
        class="input"
        :value="f.hospitalQ"
        :placeholder="tr('Type a hospital name', 'হাসপাতালের নাম লিখুন')"
        :aria-label="tr('Hospital name', 'হাসপাতালের নাম')"
        @input="setF({ hospitalQ: ($event.target as HTMLInputElement).value.slice(0, 60) })"
      />
      <div class="row wrap">
        <Button variant="outline" icon="local_hospital" @click="pickHospital = true">
          {{ f.hospital ? hospitalName(f.hospital) : tr('Choose from list', 'তালিকা থেকে বেছে নিন') }}
        </Button>
        <Button v-if="f.hospital" variant="text" @click="setF({ hospital: null })">{{ tr('Remove', 'সরান') }}</Button>
      </div>
    </div>
    <div class="col gap-6">
      <span class="t-title-s">{{ tr('Fee', 'ফি') }}</span>
      <ChoiceChips
        :options="[{ value: 0, label: tr('Any', 'যেকোনো') }, ...FEE_STEPS.map((n) => ({ value: n, label: feeLabel(n) }))]"
        :model-value="f.maxFee ?? 0"
        @update:model-value="(v: number) => setF({ maxFee: v || null })"
      />
    </div>
    <div v-if="facets?.gender || f.gender" class="col gap-6">
      <span class="t-title-s">{{ tr('Doctor', 'ডাক্তার') }}</span>
      <ChoiceChips
        :options="[{ value: '', label: tr('Any', 'যেকোনো') }, { value: 'female', label: genderLabel('female') }, { value: 'male', label: genderLabel('male') }]"
        :model-value="f.gender ?? ''"
        @update:model-value="(v: string) => setF({ gender: v === 'male' || v === 'female' ? v : null })"
      />
    </div>
    <Switch v-if="type === 'doctor'" :label="tr('Home visit', 'বাসায় যান')" :model-value="f.homeOnly" @update:model-value="(v) => setF({ homeOnly: v })" />
    <Switch v-if="type === 'doctor'" :label="OT.modeLabel('online')" :sub="OT.filterSub()" data-testid="filter-online" :model-value="f.online" @update:model-value="(v) => setF({ online: v })" />
  </FilterSheet>
  <HospitalPickerDialog v-if="pickHospital" :initial-district="cur.district" :initial-upazila-id="cur.upazilaId" @pick="hospitalPicked" @close="pickHospital = false" />
</template>
