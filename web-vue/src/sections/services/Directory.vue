<script setup lang="ts">
// সেবার তালিকা (অ্যাম্বুলেন্স / ফার্মেসি / ব্লাড ব্যাংক / প্যাথলজি) — পাঁচ তালিকার এক ছাঁচ (src/ui/listing, listings CONTRACT §১):
// উপরে ছোট অংশ (খোঁজা, এলাকার চিপ, সাজানো, ছাঁকনির চিপ), একই কার্ড, কার্ডের মতো কঙ্কাল, এক লাইনের খালি অবস্থা।
// ডেস্কটপে (দুই ভাগ) বাঁয়ে তালিকা, ডানে বাছা জিনিসের পুরো বিস্তারিত — কিছু বাছা না থাকলে প্রথমটা (ডান ভাগ কখনো খালি নয়);
// বাছলে URL বদলায় (শেয়ার করা যায়), পাতা নতুন করে খোলে না।
import { computed, onMounted, ref, watch } from 'vue';
import { Button, ErrorBox, Loading } from '@/ui/kit';
import AreaPicker from '@/ui/AreaPicker.vue';
import { useFilterArea, type AreaValue } from '@/ui/areaFilter';
import ListDetail from '@/shell/ListDetail.vue';
import DetailPane from '@/shell/DetailPane.vue';
import { useTwoPane } from '@/shell/chrome';
import { useCall } from '@/lib/query';
import { tr } from '@/lib/i18n';
import { areaLabel, ensureAreas } from '@/lib/geo';
import { useSession } from '@/lib/session';
import { useCareMode } from '@/lib/care';
import { useLoc, useNav, useSearchParams } from '@/lib/nav';
import { useQ } from '@/lib/query';
import { call } from '@/lib/api';
import RxSendFromPlace from '@/sections/health/RxSendFromPlace.vue';
import type { LastHome } from '@/sections/health/rx';
import type { PublicPrices } from '@/lib/labprice';
import ListingCard from '@/ui/listing/ListingCard.vue';
import ListingTop, { type FilterChip } from '@/ui/listing/ListingTop.vue';
import ListingSkeleton from '@/ui/listing/ListingSkeleton.vue';
import ListingEmpty from '@/ui/listing/ListingEmpty.vue';
import ListingDetail from '@/ui/listing/ListingDetail.vue';
import { LT, isSort, matchesSearch, sortListings, type Listing, type SortKey } from '@/ui/listing/model';
import {
  ambulanceTypeLabel, ambulanceTypes, kindIcon, kindLabel, recall, recordCall, remember, reportKind, setAmbActive, useCatalog,
  type Ambulance, type BloodBank, type BuyMode, type Kind, type Lab, type ListResult, type Pharmacy, type RateAspect,
} from './data';
import { ambulanceDetail, bloodBankDetail, labDetail, listingOf, pharmacyDetail } from './listing';
import { useActiveRequest, useBackFromCall } from './ambreq';
import { useAmbulanceOrigin } from './origin';
import { useMyMedRx } from './pharmacy';
import ServiceTabs from './ServiceTabs.vue';
import RatingDialog from './RatingDialog.vue';
import ReportDialog from './ReportDialog.vue';
import ActiveBanner from './ActiveBanner.vue';
import RequestDialog from './RequestDialog.vue';
import PharmacyRxInfo from './PharmacyRxInfo.vue';
import Nearest from './Nearest.vue';
import BusyBlock from './BusyBlock.vue';

const props = defineProps<{ kind: Kind; id: string | null }>();
const ACTION: Record<Kind, string> = { ambulance: 'ambulances', pharmacy: 'pharmacies', bloodbank: 'bloodbanks', lab: 'labs' };
onMounted(() => ensureAreas().catch(() => {}));
const nav = useNav();
const loc = useLoc();
const two = useTwoPane();
const [params, setParams] = useSearchParams();
const s = useSession();
const lists = useCatalog();
const [area, setArea] = useFilterArea();
const showArea = ref(false);
const busyOpen = ref(false);
const search = ref('');
const rating = ref<{ id: string; name: string; aspects?: RateAspect[] } | null>(null);
const reporting = ref<string | null>(null);

// ---------- ছাঁকনি আর সাজানো (URL-এ — বিস্তারিত খুলে ফিরে এলেও থাকে) ----------
const p = (k: string) => params.value.get(k);
const type = computed(() => (props.kind === 'ambulance' ? p('type') : null));
const openNow = computed(() => props.kind !== 'ambulance' && p('open') === '1');
const open24 = computed(() => (props.kind === 'bloodbank' || props.kind === 'pharmacy') && p('h24') === '1');
const mode = computed<BuyMode | null>(() => (props.kind === 'pharmacy' && (p('mode') === 'shop' || p('mode') === 'online') ? (p('mode') as BuyMode) : null));
const delivery = computed(() => props.kind === 'pharmacy' && p('delivery') === '1');
const home = computed(() => props.kind === 'lab' && p('home') === '1');
const setParam = (k: string, v: string | null) => {
  const n = new URLSearchParams(params.value);
  if (v == null) n.delete(k);
  else n.set(k, v);
  setParams(n, { replace: true });
};
const toggle = (k: string, v = '1') => () => setParam(k, p(k) === v ? null : v);
// মোছায় সাজানো আর বাছা প্রেসক্রিপশন (rx) থাকে
const clearFilters = () => {
  const keep = new URLSearchParams();
  for (const k of ['rx', 'sort']) if (p(k)) keep.set(k, p(k)!);
  setParams(keep, { replace: true });
  search.value = '';
};

const gps = computed(() => (s.place?.source === 'gps' ? s.place : null));
// নিকটতম: জায়গা জানা থাকলে (ফোনের জায়গা, বা উপজেলার কেন্দ্র — সার্ভার হিসাব করে)
const located = computed(() => !!gps.value || area.value?.upazilaId != null);
const sort = computed<SortKey>(() => (isSort(p('sort')) ? (p('sort') as SortKey) : located.value ? 'near' : 'rating'));

const { origin, ready, exact } = useAmbulanceOrigin(() => area.value?.district, () => props.kind === 'ambulance');
// অ্যাম্বুলেন্স ডাকুন (২.২৮.০)
const activeReq = useActiveRequest();
const requesting = ref<{ target: { id: string; name: string } | null } | null>(null);
watch(() => activeReq.value?.id ?? null, (id) => setAmbActive(id), { immediate: true });
useBackFromCall(() => (props.kind === 'ambulance' ? activeReq.value : null));
const requestAmbulance = (target: { id: string; name: string } | null) => {
  if (activeReq.value) return nav(`/services/ambulance/call/${activeReq.value.id}`);
  s.requireLogin(tr('Log in to call an ambulance', 'অ্যাম্বুলেন্স ডাকতে লগইন করুন'), () => (requesting.value = { target }));
};
// ফার্মেসি: প্রেসক্রিপশন বাছাই এখন "প্রেসক্রিপশন পাঠান"-এর ভেতরে; URL-এ rx থাকলে (প্রেসক্রিপশনের পাতা থেকে এলে) সেটার হিসাব
const pharmacy = computed(() => props.kind === 'pharmacy');
const care = useCareMode();
const myRx = useMyMedRx();
const rx = computed(() => {
  const r = pharmacy.value ? p('rx') : null;
  return r && r !== 'none' ? myRx.value.list.find((x) => x.id === r) ?? null : null;
});
const body = computed(() => {
  const kind = props.kind;
  const b: Record<string, unknown> = { district: area.value?.district, upazilaId: area.value?.upazilaId ?? null, sort: sort.value };
  if (kind === 'ambulance') {
    if (type.value) b.type = type.value;
    // কাছের খালি গাড়ি আগে (সার্ভার দূরত্ব হিসাব করে) — শুধু আসল GPS হলে; "এলাকা ধরে" হলে উপজেলার কেন্দ্র
    const o = origin.value;
    if (o && !o.byArea) Object.assign(b, { lat: o.lat, lng: o.lng, gps: true });
    return b;
  }
  if (gps.value) Object.assign(b, { lat: gps.value.lat, lng: gps.value.lng });
  if (kind === 'pharmacy') {
    Object.assign(b, { openNow: openNow.value, open24: open24.value });
    if (delivery.value) b.delivery = true;
    if (mode.value) b.mode = mode.value;
    if (rx.value) Object.assign(b, { rxId: rx.value.id, ...(care.value ? { asUid: care.value.uid } : {}) });
  }
  if (kind === 'bloodbank') {
    b.open24 = open24.value;
    if (openNow.value) b.openNow = true;
  }
  if (kind === 'lab') {
    if (openNow.value) b.openNow = true;
    if (home.value) b.homeCollection = true;
  }
  return b;
});
const q = useCall<ListResult<any> & { lastHome?: LastHome | null }>(ACTION[props.kind], body, () => ({ enabled: !!area.value?.district && ready.value }));
const items = computed(() => q.data?.items ?? []);
const busy = computed(() => (q.data?.busy ?? []) as Ambulance[]);
watch(
  () => q.data,
  (d) => {
    remember(props.kind, d?.items);
    remember(props.kind, d?.busy);
  },
  { immediate: true },
);
const lastHome = computed(() => q.data?.lastHome ?? null);
const byId = computed(() => new Map<string, any>([...items.value, ...busy.value].map((x) => [x.id, x])));

// কার্ড: সার্ভার না পারলে (পুরোনো সার্ভার) এখানেই ছাঁকা আর সাজানো
const all = computed(() => items.value.map((x) => listingOf(props.kind, x, lists.value)));
const shown = computed(() => {
  const list = all.value.filter((l) => {
    if (!matchesSearch(l, search.value)) return false;
    if (openNow.value && l.openNow === false) return false;
    if (props.kind === 'bloodbank' && open24.value && !l.badges.some((b) => b.text === LT.h24())) return false;
    if (delivery.value) {
      const x = byId.value.get(l.id) as Pharmacy | undefined;
      if (!x?.delivery && !x?.online) return false;
    }
    if (home.value && !(byId.value.get(l.id) as Lab | undefined)?.homeCollection) return false;
    return true;
  });
  return sortListings(list, sort.value);
});
const busyShown = computed(() => busy.value.map((x) => listingOf('ambulance', x, lists.value)).filter((l) => matchesSearch(l, search.value)));

// ---------- চিপ ----------
const chips = computed<FilterChip[]>(() => {
  const k = props.kind;
  if (k === 'ambulance') return ambulanceTypes(lists.value).map((t) => ({ key: 'type-' + t, label: ambulanceTypeLabel(lists.value, t), on: type.value === t, toggle: toggle('type', t) }));
  if (k === 'pharmacy') {
    return [
      { key: 'shop', label: tr('At the shop', 'দোকানে গিয়ে'), icon: 'storefront', on: mode.value === 'shop', toggle: toggle('mode', 'shop') }, // bn-ok
      { key: 'online', label: tr('Online order', 'অনলাইন অর্ডার'), icon: 'local_shipping', on: mode.value === 'online', toggle: toggle('mode', 'online') }, // bn-ok
      { key: 'open', label: tr('Open now', 'এখন খোলা'), on: openNow.value, toggle: toggle('open') },
      { key: 'h24', label: LT.h24(), on: open24.value, toggle: toggle('h24') },
      { key: 'delivery', label: tr('Delivery', 'ডেলিভারি'), on: delivery.value, toggle: toggle('delivery') },
    ];
  }
  if (k === 'bloodbank') {
    return [
      { key: 'open', label: tr('Open now', 'এখন খোলা'), on: openNow.value, toggle: toggle('open') },
      { key: 'h24', label: LT.h24(), on: open24.value, toggle: toggle('h24') },
    ];
  }
  return [
    { key: 'open', label: tr('Open now', 'এখন খোলা'), on: openNow.value, toggle: toggle('open') },
    { key: 'home', label: LT.homeSample(), on: home.value, toggle: toggle('home') },
  ];
});
const filtering = computed(() => chips.value.some((c) => c.on) || !!search.value.trim());

// ---------- কাজ ----------
const listPath = computed(() => `/services/${props.kind}${loc.value.search}`);
const open = (eid: string) => nav(`/services/${props.kind}/${eid}${loc.value.search}`);
const rateGate = (id: string, name: string, aspects?: RateAspect[]) => () =>
  s.requireLogin(tr('Log in to do this', 'এর জন্য লগইন করুন'), () => (rating.value = { id, name, aspects }));
const reportGate = (id: string) => () => s.requireLogin(tr('Log in to report', 'রিপোর্ট করতে লগইন করুন'), () => (reporting.value = id));
const reportable = computed(() => props.kind !== 'lab');
const onCall = (id: string) => () => recordCall(props.kind, id, s.guest);
const callPrimary = computed(() => props.kind === 'ambulance' || props.kind === 'bloodbank');
// বন্ধ ফার্মেসিতে "প্রেসক্রিপশন পাঠান" নয়
const noPrimary = (l: Listing) => props.kind === 'pharmacy' && l.openNow === false;

// ---------- বিস্তারিত: বাছা, না হলে (ডেস্কটপে) প্রথমটা ----------
const selId = computed(() => props.id ?? (two.value ? shown.value[0]?.id ?? null : null));
const raw = computed(() => {
  const id = selId.value;
  return id ? byId.value.get(id) ?? recall<any>(props.kind, id) ?? null : null;
});
const sel = computed(() => (raw.value ? listingOf(props.kind, raw.value, lists.value) : null));
// প্যাথলজি: পুরো পরীক্ষা ও দামের তালিকা (lab_prices_public)
const labId = computed(() => (props.kind === 'lab' && raw.value?.prices ? (raw.value as Lab).id : null));
const prices = useQ<{ prices: PublicPrices | null }>(() => ({
  queryKey: ['lab_prices_public', labId.value],
  queryFn: () => call('lab_prices_public', { labId: labId.value }),
  enabled: !!labId.value,
  staleTime: 60_000,
}));
const detail = computed(() => {
  const x = raw.value;
  if (!x) return null;
  const k = props.kind;
  if (k === 'ambulance') return { ...ambulanceDetail(x as Ambulance, lists.value), ratingNote: '' };
  if (k === 'pharmacy') return pharmacyDetail(x as Pharmacy, mode.value === 'online');
  if (k === 'bloodbank') return { ...bloodBankDetail(x as BloodBank, lists.value), ratingNote: '' };
  const full = !(x as Lab).prices ? null : prices.data ? prices.data.prices : prices.isError ? null : undefined;
  return { ...labDetail(x as Lab, full), ratingNote: '' };
});
const closeDetail = () => nav(listPath.value);

function onPicked(v: AreaValue) {
  setArea({ district: v.district, upazilaId: v.upazilaId ?? null, unionId: null });
  showArea.value = false;
}
</script>

<template>
  <ListDetail :has-detail="!!id" :auto="two">
    <div class="list-inner lst-page">
      <ServiceTabs :kind="kind" :sub="kind === 'pharmacy' ? 'list' : undefined" />
      <template v-if="kind === 'ambulance'">
        <ActiveBanner v-if="activeReq" :r="activeReq" />
        <Nearest v-if="area" :list="items as Ambulance[]" :id="q.data?.nearestId" :by-area="!!origin?.byArea" :lists="lists" @request="requestAmbulance(null)" />
        <a class="lst-999" href="tel:999" data-testid="call-999">{{ tr('Emergency? 999 is free, 24 hours', 'জরুরি হলে ৯৯৯ — বিনামূল্যে, ২৪ ঘণ্টা') }}</a>
      </template>
      <ListingTop
        v-model:search="search"
        :kind="kind"
        :area-label="area ? areaLabel(area.district, area.upazilaId) : ''"
        :on-area="() => (showArea = true)"
        :sort="sort"
        :sorts="['near', 'rating', 'open']"
        :chips="chips"
        :on-clear="clearFilters"
        @sort="(k) => setParam('sort', k)"
      />
      <ListingEmpty v-if="!area" icon="location_on" :title="LT.chooseArea()" :on-area="() => (showArea = true)" />
      <ListingSkeleton v-else-if="q.isLoading || !ready" :n="4" />
      <ErrorBox v-else-if="q.isError" :error="q.error!.message" :on-retry="() => q.refetch()" />
      <template v-else>
        <div v-if="shown.length" class="lst-list">
          <ListingCard
            v-for="l in shown"
            :key="l.id"
            :l="l"
            :selected="l.id === selId"
            :on-open="() => open(l.id)"
            :call-primary="callPrimary"
            :no-primary="noPrimary(l)"
            :on-call="onCall(l.id)"
            :on-report="reportable && !s.guest ? reportGate(l.id) : undefined"
          >
            <template v-if="kind === 'pharmacy'" #primary>
              <RxSendFromPlace :pharmacy="byId.get(l.id) as Pharmacy" :mode="mode ?? undefined" :rx="rx" :last="lastHome" card />
            </template>
            <template v-else-if="kind === 'lab'" #primary>
              <RxSendFromPlace :lab="byId.get(l.id) as Lab" card :label="LT.sendTest()" />
            </template>
          </ListingCard>
        </div>
        <ListingEmpty
          v-else-if="filtering && all.length"
          :icon="kindIcon(kind)"
          :title="LT.noMatch()"
          :on-clear="clearFilters"
        />
        <ListingEmpty
          v-else
          :icon="kindIcon(kind)"
          :title="kind === 'ambulance' && busy.length ? tr('All ambulances here are on a trip now', 'এই এলাকার সব অ্যাম্বুলেন্স এখন ট্রিপে আছে') : LT.empty(kind)"
          :on-area="filtering ? undefined : () => (showArea = true)"
          :on-clear="filtering ? clearFilters : undefined"
        />
        <BusyBlock v-if="kind === 'ambulance' && busyShown.length" :open="busyOpen || !shown.length" :count="busyShown.length" @toggle="busyOpen = !(busyOpen || !shown.length)">
          <div class="lst-list">
            <ListingCard
              v-for="l in busyShown"
              :key="l.id"
              :l="l"
              :selected="l.id === selId"
              :on-open="() => open(l.id)"
              call-primary
              :on-call="onCall(l.id)"
              :on-report="!s.guest ? reportGate(l.id) : undefined"
            />
          </div>
        </BusyBlock>
      </template>
    </div>
    <template #detail>
      <template v-if="id || two">
        <ListingDetail
          v-if="sel && detail"
          :key="sel.id"
          :l="sel"
          :title="kindLabel(kind)"
          :back-to="listPath"
          :facts="detail.facts"
          :sections="detail.sections"
          :rating-note="detail.ratingNote"
          :call-primary="callPrimary && !(kind === 'ambulance' && !(raw.busySince ?? 0))"
          :no-primary="noPrimary(sel)"
          :on-call="onCall(sel.id)"
          :on-rate="raw.canRate ? rateGate(sel.id, sel.name, raw.rateAspects) : undefined"
          :on-report="reportable && !s.guest ? reportGate(sel.id) : undefined"
          :closable="!!id"
          @close="closeDetail"
        >
          <template v-if="kind === 'pharmacy'" #top><PharmacyRxInfo :p="raw as Pharmacy" :mode="mode" big /></template>
          <template v-if="kind === 'ambulance'" #primary>
            <Button icon="ambulance" data-testid="amb-request-one" @click="requestAmbulance({ id: raw.id, name: (raw as Ambulance).name })">{{ tr('Call ambulance', 'অ্যাম্বুলেন্স ডাকুন') }}</Button>
          </template>
          <template v-else-if="kind === 'pharmacy'" #primary><RxSendFromPlace :pharmacy="raw as Pharmacy" :mode="mode ?? undefined" :rx="rx" :last="lastHome" /></template>
          <template v-else-if="kind === 'lab'" #primary><RxSendFromPlace :lab="raw as Lab" :label="LT.sendTest()" /></template>
        </ListingDetail>
        <DetailPane v-else-if="area && (q.isLoading || q.isFetching || !ready)" :title="kindLabel(kind)" :back-to="listPath"><Loading /></DetailPane>
        <DetailPane v-else :title="kindLabel(kind)" :back-to="listPath">
          <ListingEmpty :icon="kindIcon(kind)" :title="id ? tr('Not found', 'পাওয়া যায়নি') : area ? LT.empty(kind) : LT.chooseArea()" :on-area="id ? undefined : () => (showArea = true)" />
        </DetailPane>
      </template>
    </template>
  </ListDetail>
  <AreaPicker v-if="showArea" :value="area ? { district: area.district, upazilaId: null } : null" @close="showArea = false" @picked="onPicked" />
  <RequestDialog
    v-if="requesting && area"
    :district="area.district"
    :upazila-id="area.upazilaId ?? null"
    :type="type"
    :gps="exact"
    :target="requesting.target"
    @close="requesting = null"
  />
  <RatingDialog v-if="rating" :kind="kind" :id="rating.id" :name="rating.name" :aspects="rating.aspects" @close="rating = null" />
  <ReportDialog v-if="reporting" :kind="reportKind(kind)" :id="reporting" @close="reporting = null" />
</template>
