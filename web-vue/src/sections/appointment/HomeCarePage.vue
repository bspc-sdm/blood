<script setup lang="ts">
// বাসায় সেবা: যে ডাক্তার আর নার্স রোগীর বাসায় যান (অ্যাপের HomeCare.kt)। অ্যাপে অনুরোধ/গ্রহণ নেই —
// রোগী সরাসরি ফোন করেন (home_called দিয়ে হিসাব), ফোনের পর রেটিং (home_rate)। বাছা মানুষ URL-এ।
import { computed, ref } from 'vue';
import { ActiveChips, Button, EmptyState, ErrorBox, FilterBar, IconButton, InfoText, PillTabs, SkeletonCards, Switch, toast } from '@/ui/kit';
import { useFilterArea } from '@/ui/areaFilter';
import DetailPane from '@/shell/DetailPane.vue';
import ListDetail from '@/shell/ListDetail.vue';
import { usePageTitle } from '@/shell/chrome';
import { call, errorText } from '@/lib/api';
import { useCall } from '@/lib/query';
import { useSession } from '@/lib/session';
import { tr } from '@/lib/i18n';
import { areaLabel, district as findDistrict } from '@/lib/geo';
import { useNav, useSearchParams } from '@/lib/nav';
import { homeServiceLabel, homeServicesOf, parseHomePro, useCatalog, type HomePro } from './model';
import { T as PT, physioOn } from '@/lib/practice';
import { useAreasReady, type AreaSel } from './shared';
import AreaSheet from './AreaSheet.vue';
import FilterSheet from './FilterSheet.vue';
import FilterChoice from './FilterChoice.vue';
import StarRatingDialog from './StarRatingDialog.vue';
import ReasonReportDialog from './ReasonReportDialog.vue';
import HomeProCard from './HomeProCard.vue';
import HomeProDetail from './HomeProDetail.vue';

const props = defineProps<{ detailId: string | null }>();
usePageTitle(() => tr('Home care', 'বাসায় সেবা'));
useCatalog();
useAreasReady();
const nav = useNav();
const [sp, setSp] = useSearchParams();
const s = useSession();
// ফিজিওথেরাপিস্ট বাসায় যান (pro_physio চালু থাকলে নিজের ট্যাব)
const type = computed(() => (sp.value.get('t') === 'nurse' ? 'nurse' : sp.value.get('t') === 'physio' && physioOn() ? 'physio' : 'doctor'));
const hasGps = computed(() => s.place?.source === 'gps');
const [fa, setFa] = useFilterArea();
// GPS থাকলে শুরুতে নিজের কাছে, নইলে নিজের জেলায়
const nearMe = ref<boolean | null>(null);
const near = computed(() => nearMe.value ?? hasGps.value);
const area = ref<AreaSel | null>(null);
const cur = computed<AreaSel>(() => area.value ?? { district: fa.value?.district ?? null, upazilaId: fa.value?.upazilaId ?? null, unionId: null });
const service = ref<string | null>(null);
const female = ref(false);
const showArea = ref(false);
const showFilter = ref(false);
const info = ref<string | null>(null);

const body = computed(() => {
  const b: Record<string, unknown> = { type: type.value, female: female.value };
  const place = s.place;
  if (near.value && place) {
    b.lat = place.lat;
    b.lng = place.lng;
    // নিজের জেলাও: যাঁরা লোকেশন না দিয়ে উপজেলা বেছেছেন তাঁরাও যেন আসেন
    if (findDistrict(place.placeName)) b.district = place.placeName;
  } else {
    if (cur.value.district) b.district = cur.value.district;
    if (cur.value.upazilaId != null) b.upazilaId = cur.value.upazilaId;
  }
  if (service.value) b.service = service.value;
  return b;
});
const q = useCall<{ items: any[] }>('home_providers', body);
const list = computed(() => (q.data?.items ?? []).map(parseHomePro));
const inList = computed(() => (props.detailId ? list.value.find((p) => p.uid === props.detailId) ?? null : null));
// সরাসরি খোলা লিংক: এখনকার এলাকায় না থাকলে সব জায়গার তালিকা থেকে
const fb = useCall<{ items: any[] }>('home_providers', () => ({ type: type.value, female: false }), () => ({ enabled: !!props.detailId && !inList.value && !q.isLoading }));
const selected = computed(() => inList.value ?? (props.detailId ? (fb.data?.items ?? []).map(parseHomePro).find((p) => p.uid === props.detailId) ?? null : null));

const qs = computed(() => sp.value.toString());
const listPath = computed(() => '/appointment/home-care' + (qs.value ? `?${qs.value}` : ''));
const rating = ref<HomePro | null>(null);
const reporting = ref<HomePro | null>(null);

const onCall = (p: HomePro) => {
  if (!s.guest) call('home_called', { uid: p.uid }).catch(() => {});
};
const callNow = (p: HomePro) => {
  onCall(p);
  window.location.href = `tel:${p.phone}`;
};
const clearFilters = () => {
  service.value = null;
  female.value = false;
};
function changeTab(k: string) {
  service.value = null;
  setSp(k === 'doctor' ? {} : { t: k }, { replace: true });
  if (props.detailId) nav(k === 'doctor' ? '/appointment/home-care' : `/appointment/home-care?t=${k}`, { replace: true });
}
function pickNear() {
  nearMe.value = true;
  showArea.value = false;
}
function areaPicked(v: AreaSel) {
  nearMe.value = false;
  area.value = v;
  if (v.district) setFa({ district: v.district, upazilaId: v.upazilaId, unionId: null });
  showArea.value = false;
}
async function rate(stars: number, comment: string) {
  const p = rating.value!;
  await call('home_rate', { uid: p.uid, rating: stars, comment });
  rating.value = null;
  const m = tr('Thanks for the rating!', 'রেটিং দেওয়ার জন্য ধন্যবাদ!');
  info.value = m;
  toast(m);
  q.refetch();
}
async function report(reason: string) {
  const p = reporting.value!;
  await call('report', { kind: 'homecare', id: p.uid, reason });
  reporting.value = null;
  const m = tr('Reported. An admin will review it.', 'রিপোর্ট করা হয়েছে। অ্যাডমিন দেখবেন।');
  info.value = m;
  toast(m);
}
</script>

<template>
  <ListDetail :has-detail="!!detailId">
    <div class="list-inner appt-pre">
      <div class="row appt-title">
        <IconButton icon="arrow_back" :label="tr('Back', 'ফিরে যান')" @click="nav('/appointment')" />
        <h1 class="t-title-l grow appt-h1">{{ tr('Home care', 'বাসায় সেবা') }}</h1>
      </div>
      <div class="appt-bleed">
        <PillTabs
          secondary
          :tabs="[
            { key: 'doctor', label: tr('Doctor at home', 'বাসায় ডাক্তার'), icon: 'stethoscope' },
            { key: 'nurse', label: tr('Nurse at home', 'বাসায় নার্স'), icon: 'medical_services' },
            ...(physioOn() ? [{ key: 'physio', label: PT.physioHome(), icon: 'directions_run' }] : []),
          ]"
          :value="type"
          @change="changeTab"
        />
      </div>
      <FilterBar
        :area-label="near ? tr('Near me', 'আমার কাছে') : cur.district ? areaLabel(cur.district, cur.upazilaId) : tr('All districts', 'সব জেলা')"
        :on-area="() => (showArea = true)"
        :filter-count="[service, female || null].filter(Boolean).length"
        :on-filter="() => (showFilter = true)"
      >
        <ActiveChips
          :items="[
            ...(service ? [{ label: homeServiceLabel(service), onRemove: () => (service = null) }] : []),
            ...(female ? [{ label: tr('Female only', 'শুধু মহিলা'), onRemove: () => (female = false) }] : []),
          ]"
        />
        <p v-if="type === 'nurse'" class="t-body-s muted">{{ tr('Injection or saline needs a doctor\'s prescription.', 'ইনজেকশন বা স্যালাইনে ডাক্তারের প্রেসক্রিপশন লাগবে।') }}</p>
      </FilterBar>
      <InfoText :text="info" />
      <SkeletonCards v-if="q.isLoading" :n="4" :h="170" />
      <ErrorBox v-else-if="q.error" :error="errorText(q.error)" :on-retry="() => q.refetch()" />
      <EmptyState
        v-else-if="!list.length"
        :icon="type === 'physio' ? 'directions_run' : type === 'doctor' ? 'stethoscope' : 'medical_services'"
        :title="type === 'physio' ? PT.physioHomeEmpty() : type === 'doctor' ? tr('No doctor who visits homes here yet', 'এখানে বাসায় যান এমন ডাক্তার এখনো নেই') : tr('No nurse who visits homes here yet', 'এখানে বাসায় যান এমন নার্স এখনো নেই')"
        :text="tr('Try another area or remove a filter.', 'অন্য এলাকা দিয়ে বা ফিল্টার সরিয়ে দেখুন।')"
      >
        <Button v-if="service || female" variant="text" @click="clearFilters">{{ tr('Remove filters', 'ফিল্টার সরান') }}</Button>
      </EmptyState>
      <template v-else>
        <HomeProCard
          v-for="p in list"
          :key="p.uid"
          :p="p"
          :selected="p.uid === detailId"
          :on-open="() => nav(`/appointment/home-care/${p.uid}${qs ? `?${qs}` : ''}`)"
          :on-call="() => callNow(p)"
          :on-rate="p.canRate ? () => (rating = p) : null"
        />
      </template>
    </div>
    <template #detail>
      <DetailPane v-if="selected" :title="selected.name" :back-to="listPath" hide-title-wide>
        <HomeProDetail
          :p="selected"
          :on-close="() => nav(listPath)"
          :on-call="() => onCall(selected!)"
          :on-rate="selected.canRate ? () => (rating = selected) : null"
          :on-report="!s.guest && selected.uid !== s.uid ? () => (reporting = selected) : null"
        />
      </DetailPane>
      <DetailPane v-else :title="tr('Home care', 'বাসায় সেবা')" :back-to="listPath">
        <SkeletonCards v-if="fb.isLoading || q.isLoading" :n="2" :h="160" />
        <EmptyState v-else icon="home_health" :title="tr('Not found', 'পাওয়া যায়নি')" />
      </DetailPane>
    </template>
    <template #placeholder><EmptyState icon="home_health" :title="tr('Home care', 'বাসায় সেবা')" /></template>
  </ListDetail>
  <AreaSheet v-if="showArea" :value="cur" allow-all :near-me="hasGps ? near : null" :on-near-me="pickNear" @pick="areaPicked" @close="showArea = false" />
  <FilterSheet v-if="showFilter" :title="tr('Filter', 'ফিল্টার')" :on-clear="service || female ? clearFilters : undefined" @close="showFilter = false">
    <FilterChoice :title="tr('Service', 'সেবা')" :options="homeServicesOf(type)" :value="service" :label="homeServiceLabel" @change="(v) => (service = v)" />
    <Switch v-model="female" :label="tr('Female only', 'শুধু মহিলা')" />
  </FilterSheet>
  <StarRatingDialog v-if="rating" :title="tr('Rate ', 'রেটিং: ') + rating.name" :on-submit="rate" @close="rating = null" />
  <ReasonReportDialog v-if="reporting" :on-submit="report" @close="reporting = null" />
</template>
