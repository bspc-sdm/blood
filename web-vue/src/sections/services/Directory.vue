<script setup lang="ts">
// সেবার তালিকা: অ্যাম্বুলেন্স / ফার্মেসি / ব্লাড ব্যাংক / প্যাথলজি — এলাকা, ফিল্টার, কার্ড, আর পাশে (বা পুরো পাতায়) বিস্তারিত
import { computed, onMounted, ref, watch } from 'vue';
import { RouterLink } from 'vue-router';
import { ActiveChips, Button, ChoiceChips, EmptyState, ErrorBox, FilterBar, Loading, SkeletonCards, Switch } from '@/ui/kit';
import AreaPicker from '@/ui/AreaPicker.vue';
import { useFilterArea, type AreaValue } from '@/ui/areaFilter';
import ListDetail from '@/shell/ListDetail.vue';
import DetailPane from '@/shell/DetailPane.vue';
import { useCall } from '@/lib/query';
import { tr } from '@/lib/i18n';
import { areaLabel, ensureAreas } from '@/lib/geo';
import { useSession } from '@/lib/session';
import { useCareMode } from '@/lib/care';
import { useLoc, useNav, useSearchParams } from '@/lib/nav';
import RxSendFromPlace from '@/sections/health/RxSendFromPlace.vue';
import type { LastHome } from '@/sections/health/rx';
import {
  ambulanceEntry, ambulanceTypeLabel, ambulanceTypes, bloodBankEntry, kindIcon, kindLabel, labEntry, pharmacyEntry, recall, remember, setAmbActive, useCatalog,
  type Ambulance, type BloodBank, type Entry, type Kind, type Lab, type ListResult, type Pharmacy, type RateAspect,
} from './data';
import { useActiveRequest, useBackFromCall } from './ambreq';
import { useAmbulanceOrigin } from './origin';
import { useBuyMode, useMyMedRx } from './pharmacy';
import ServiceTabs from './ServiceTabs.vue';
import ServiceCard from './ServiceCard.vue';
import EntryDetail from './EntryDetail.vue';
import Emergency999 from './Emergency999.vue';
import FilterSheet from './FilterSheet.vue';
import RatingDialog from './RatingDialog.vue';
import ReportDialog from './ReportDialog.vue';
import ActiveBanner from './ActiveBanner.vue';
import RequestDialog from './RequestDialog.vue';
import BuyModePicker from './BuyModePicker.vue';
import RxSelector from './RxSelector.vue';
import PharmacyRxInfo from './PharmacyRxInfo.vue';
import Nearest from './Nearest.vue';
import BusyBlock from './BusyBlock.vue';
import Summary from './Summary.vue';
import LabEntryDetail from './LabEntryDetail.vue';

const props = defineProps<{ kind: Kind; id: string | null }>();
const ACTION: Record<Kind, string> = { ambulance: 'ambulances', pharmacy: 'pharmacies', bloodbank: 'bloodbanks', lab: 'labs' };
onMounted(() => ensureAreas().catch(() => {}));
const nav = useNav();
const loc = useLoc();
const [params, setParams] = useSearchParams();
const s = useSession();
const lists = useCatalog();
const [area, setArea] = useFilterArea();
const showArea = ref(false);
const showFilter = ref(false);
const busyOpen = ref(false);
const rating = ref<{ id: string; name: string; aspects?: RateAspect[] } | null>(null);
const reporting = ref<string | null>(null);

// ফিল্টার URL-এ (বিস্তারিত খুলে ফিরে এলেও থাকে)
const type = computed(() => (props.kind === 'ambulance' ? params.value.get('type') : null));
const openNow = computed(() => props.kind === 'pharmacy' && params.value.get('open') === '1');
// ২৪ ঘণ্টা খোলা: ব্লাড ব্যাংক আর ফার্মেসি (রাতে ওষুধ দরকার হলে)
const open24 = computed(() => (props.kind === 'bloodbank' || props.kind === 'pharmacy') && params.value.get('h24') === '1');
const setParam = (k: string, v: string | null) => {
  const p = new URLSearchParams(params.value);
  if (v == null) p.delete(k);
  else p.set(k, v);
  setParams(p, { replace: true });
};
// ফিল্টার মোছায় বাছা প্রেসক্রিপশন (rx) থাকে
const clearFilters = () => setParams(new URLSearchParams(params.value.get('rx') ? { rx: params.value.get('rx')! } : {}), { replace: true });

const gps = computed(() => (s.place?.source === 'gps' ? s.place : null));
const { origin, ready, exact } = useAmbulanceOrigin(() => area.value?.district, () => props.kind === 'ambulance');
// অ্যাম্বুলেন্স ডাকুন (২.২৮.০): নিজের চালু অনুরোধ, নিশ্চিত করার ঘর (target = বিস্তারিত থেকে একটা গাড়ি)
const activeReq = useActiveRequest();
const requesting = ref<{ target: { id: string; name: string } | null } | null>(null);
watch(() => activeReq.value?.id ?? null, (id) => setAmbActive(id), { immediate: true });
useBackFromCall(() => (props.kind === 'ambulance' ? activeReq.value : null));
const requestAmbulance = (target: { id: string; name: string } | null) => {
  if (activeReq.value) return nav(`/services/ambulance/call/${activeReq.value.id}`);
  s.requireLogin(tr('Log in to call an ambulance', 'অ্যাম্বুলেন্স ডাকতে লগইন করুন'), () => (requesting.value = { target }));
};
// ফার্মেসি (২.৩২.০): আগে কীভাবে কিনবেন (মনে থাকে), আর প্রেসক্রিপশন — URL-এর rx (না থাকলে নতুনটা, "none" = ছাড়া)
const pharmacy = computed(() => props.kind === 'pharmacy');
const [buyMode, setBuyMode] = useBuyMode();
const care = useCareMode();
const myRx = useMyMedRx();
const rx = computed(() => {
  const rxParam = pharmacy.value ? params.value.get('rx') : null;
  return !pharmacy.value || rxParam === 'none' ? null : myRx.value.list.find((r) => r.id === rxParam) ?? myRx.value.list[0] ?? null;
});
const rxReady = computed(() => !pharmacy.value || s.guest || myRx.value.ready);
const body = computed(() => {
  const kind = props.kind;
  const b: Record<string, unknown> = { district: area.value?.district, upazilaId: area.value?.upazilaId ?? null };
  if (kind === 'ambulance' && type.value) b.type = type.value;
  // কাছের খালি গাড়ি আগে (সার্ভার দূরত্ব হিসাব করে) — শুধু আসল GPS হলে; "এলাকা ধরে" হলে কিছুই নয় (উপজেলা আগে)
  const o = origin.value;
  if (kind === 'ambulance' && o && !o.byArea) Object.assign(b, { lat: o.lat, lng: o.lng, gps: true });
  if (kind === 'pharmacy') {
    Object.assign(b, { lat: gps.value?.lat ?? null, lng: gps.value?.lng ?? null, openNow: openNow.value, open24: open24.value, mode: buyMode.value });
    // প্রেসক্রিপশনের হিসাব এক ডাকেই; কেয়ারগিভার মোডে রোগীর হয়ে (তালিকা নিজে সাধারণত নিজের হয়েই)
    if (rx.value) Object.assign(b, { rxId: rx.value.id, ...(care.value ? { asUid: care.value.uid } : {}) });
  }
  if (kind === 'bloodbank') b.open24 = open24.value;
  return b;
});
const q = useCall<ListResult<any> & { lastHome?: LastHome | null }>(ACTION[props.kind], body, () => ({
  enabled: !!area.value?.district && ready.value && rxReady.value && (!pharmacy.value || !!buyMode.value),
}));
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

const toEntry = (x: any): Entry => {
  const kind = props.kind;
  return kind === 'ambulance' ? ambulanceEntry(x as Ambulance, lists.value) : kind === 'pharmacy' ? pharmacyEntry(x as Pharmacy, buyMode.value) : kind === 'bloodbank' ? bloodBankEntry(x as BloodBank, lists.value) : labEntry(x as Lab);
};
const byId = computed(() => new Map<string, any>(items.value.map((x) => [x.id, x])));
const lastHome = computed(() => q.data?.lastHome ?? null);
const entries = computed(() => items.value.map(toEntry));
const busyEntries = computed(() => busy.value.map(toEntry));

const listPath = computed(() => `/services/${props.kind}${loc.value.search}`);
const open = (eid: string) => nav(`/services/${props.kind}/${eid}${loc.value.search}`);
const rateGate = (e: Entry) => () => s.requireLogin(tr('Log in to do this', 'এর জন্য লগইন করুন'), () => (rating.value = { id: e.id, name: e.title, aspects: e.rateAspects }));
const reportGate = (e: Entry) => () => s.requireLogin(tr('Log in to report', 'রিপোর্ট করতে লগইন করুন'), () => (reporting.value = e.id));
const pharmOf = (e: Entry) => (pharmacy.value ? (byId.value.get(e.id) as Pharmacy | undefined) : undefined);

// ---------- ফিল্টার ----------
const filterCount = computed(() => (type.value ? 1 : 0) + (openNow.value ? 1 : 0) + (open24.value ? 1 : 0));
const chips = computed(() => [
  ...(type.value ? [{ label: ambulanceTypeLabel(lists.value, type.value), onRemove: () => setParam('type', null) }] : []),
  ...(openNow.value ? [{ label: tr('Open now', 'এখন খোলা'), onRemove: () => setParam('open', null) }] : []),
  ...(open24.value ? [{ label: tr('24 hours', '২৪ ঘণ্টা'), onRemove: () => setParam('h24', null) }] : []),
]);
const typeOpts = computed(() => ambulanceTypes(lists.value).map((t) => ({ value: t, label: ambulanceTypeLabel(lists.value, t) })));
const ambItems = computed(() => items.value as Ambulance[]);

// ---------- বিস্তারিত ----------
const raw = computed(() => (props.id ? items.value.find((x) => x.id === props.id) ?? busy.value.find((x) => x.id === props.id) ?? recall<any>(props.kind, props.id) : null));
const detailEntry = computed(() => (raw.value ? toEntry(raw.value) : null));
function onPicked(v: AreaValue) {
  setArea({ district: v.district, upazilaId: v.upazilaId ?? null, unionId: null });
  showArea.value = false;
}
</script>

<template>
  <ListDetail :has-detail="!!id">
    <div class="list-inner">
      <ServiceTabs :kind="kind" :sub="kind === 'pharmacy' ? 'list' : undefined" />
      <FilterBar
        :area-label="area ? areaLabel(area.district, area.upazilaId) : ''"
        :on-area="() => (showArea = true)"
        :filter-count="filterCount"
        :on-filter="kind === 'lab' ? undefined : () => (showFilter = true)"
      >
        <ActiveChips :items="chips" />
      </FilterBar>
      <ActiveBanner v-if="kind === 'ambulance' && activeReq" :r="activeReq" />
      <Nearest
        v-if="kind === 'ambulance' && area && !q.isLoading && ambItems.some((a) => a.available)"
        :list="ambItems"
        :id="q.data?.nearestId"
        :by-area="!!origin?.byArea"
        :lists="lists"
        @request="requestAmbulance(null)"
      />
      <Emergency999 v-if="kind === 'ambulance'" />
      <p v-if="kind === 'bloodbank'" class="t-body-s muted">{{ tr('Buying or selling blood is illegal; only processing costs apply.', 'রক্ত কেনা-বেচা অবৈধ; শুধু পরীক্ষা ও প্রসেসিং খরচ লাগে।') }}</p>
      <BuyModePicker v-if="pharmacy" :value="buyMode" @change="setBuyMode" />
      <RxSelector v-if="pharmacy && buyMode && myRx.list.length" :list="myRx.list" :value="rx" @change="(v) => setParam('rx', v)" />
      <template v-if="pharmacy && !buyMode" />
      <EmptyState v-else-if="!area" icon="location_on" :title="tr('Choose area', 'এলাকা বেছে নিন')">
        <Button @click="showArea = true">{{ tr('Choose area', 'এলাকা বেছে নিন') }}</Button>
      </EmptyState>
      <SkeletonCards v-else-if="q.isLoading || !rxReady" :n="4" :h="150" />
      <ErrorBox v-else-if="q.isError" :error="q.error!.message" :on-retry="() => q.refetch()" />
      <template v-else>
        <div v-if="entries.length" class="grid-cards">
          <ServiceCard
            v-for="e in entries"
            :key="e.id"
            :e="e"
            :selected="e.id === id"
            :on-open="() => open(e.id)"
            :on-rate="e.canRate ? rateGate(e) : undefined"
            :on-report="e.reportable ? reportGate(e) : undefined"
          >
            <template v-if="pharmOf(e)" #main><RxSendFromPlace :pharmacy="pharmOf(e)" :mode="buyMode ?? undefined" :rx="rx" :last="lastHome" card /></template>
            <template v-if="pharmOf(e)" #info><PharmacyRxInfo :p="pharmOf(e)!" :mode="buyMode" /></template>
          </ServiceCard>
        </div>
        <!-- খালি অবস্থা -->
        <EmptyState
          v-else-if="kind === 'ambulance'"
          icon="ambulance"
          :title="(q.data?.busyCount ?? busy.length) > 0 ? tr('All ambulances here are on a trip now', 'এই এলাকার সব অ্যাম্বুলেন্স এখন ট্রিপে আছে') : tr('No ambulance listed in this area yet', 'এই এলাকায় এখনো কোনো অ্যাম্বুলেন্স যোগ হয়নি')"
          :text="tr('Know an ambulance service? They can join from &quot;My work&quot;.', 'কোনো অ্যাম্বুলেন্স সার্ভিস চিনলে &quot;আমার কাজ&quot; থেকে যোগ হতে বলুন।')"
        >
          <RouterLink to="/work/add" class="btn text small">{{ tr('Add yours from My work', '"আমার কাজ" থেকে যোগ করুন') }}</RouterLink>
        </EmptyState>
        <EmptyState
          v-else-if="kind === 'pharmacy' && buyMode === 'online'"
          icon="local_shipping"
          :title="tr('No pharmacy delivers to your area yet', 'আপনার এলাকায় এখনো কোনো ফার্মেসি অনলাইনে ডেলিভারি দেয় না')"
          :text="tr('Choose &quot;I\'ll buy at the shop&quot; to see all pharmacies nearby.', '&quot;দোকানে গিয়ে কিনব&quot; বাছলে কাছের সব ফার্মেসি দেখাবে।')"
        >
          <Button variant="tonal" icon="storefront" @click="setBuyMode('shop')">{{ tr("I'll buy at the shop", 'দোকানে গিয়ে কিনব') }}</Button>
        </EmptyState>
        <EmptyState
          v-else-if="kind === 'pharmacy'"
          icon="storefront"
          :title="tr('No pharmacy in the app here yet', 'এখানে এখনো অ্যাপে কোনো ফার্মেসি নেই')"
          :text="tr('Know a pharmacy? They can join from &quot;My work&quot;.', 'কোনো ফার্মেসি চিনলে &quot;আমার কাজ&quot; থেকে যোগ হতে বলুন।')"
        >
          <RouterLink to="/work/add" class="btn text small">{{ tr('Add yours from My work', '"আমার কাজ" থেকে যোগ করুন') }}</RouterLink>
        </EmptyState>
        <EmptyState
          v-else-if="kind === 'bloodbank'"
          icon="bloodtype"
          :title="tr('No blood bank in the app here yet', 'এখানে এখনো অ্যাপে কোনো ব্লাড ব্যাংক নেই')"
          :text="tr('Know one? They can join from &quot;My work&quot;.', 'কোনো ব্লাড ব্যাংক চিনলে &quot;আমার কাজ&quot; থেকে যোগ হতে বলুন।')"
        >
          <RouterLink to="/work/add" class="btn text small">{{ tr('Add yours from My work', '"আমার কাজ" থেকে যোগ করুন') }}</RouterLink>
        </EmptyState>
        <EmptyState
          v-else
          icon="local_hospital"
          :title="tr('No lab in the app here yet', 'এখানে এখনো অ্যাপে কোনো প্যাথলজি নেই')"
          :text="tr('Know a diagnostic centre? They can join from &quot;My work&quot;.', 'কোনো ডায়াগনস্টিক সেন্টার চিনলে &quot;আমার কাজ&quot; থেকে যোগ হতে বলুন।')"
        >
          <RouterLink to="/work/add" class="btn text small">{{ tr('Add yours from My work', '"আমার কাজ" থেকে যোগ করুন') }}</RouterLink>
        </EmptyState>
        <BusyBlock v-if="kind === 'ambulance' && busy.length" :open="busyOpen || !entries.length" :count="busy.length" @toggle="busyOpen = !(busyOpen || !entries.length)">
          <div class="grid-cards">
            <ServiceCard
              v-for="e in busyEntries"
              :key="e.id"
              :e="e"
              :selected="e.id === id"
              :on-open="() => open(e.id)"
              :on-rate="e.canRate ? rateGate(e) : undefined"
              :on-report="e.reportable ? reportGate(e) : undefined"
            />
          </div>
        </BusyBlock>
      </template>
    </div>
    <template #detail>
      <template v-if="id">
        <LabEntryDetail v-if="raw && kind === 'lab'" :key="raw.id" :lab="raw as Lab" :back-to="listPath" />
        <EntryDetail
          v-else-if="raw && detailEntry"
          :key="raw.id"
          :e="detailEntry"
          :back-to="listPath"
          :on-rate="detailEntry.canRate ? rateGate(detailEntry) : undefined"
          :on-report="detailEntry.reportable ? reportGate(detailEntry) : undefined"
        >
          <!-- অ্যাম্বুলেন্স (২.২৮.০): নিচের বারে "ফোন"-এর পাশে "অ্যাম্বুলেন্স ডাকুন" — এই গাড়ি আগে (ট্রিপে থাকা গাড়িতে নয়); ফার্মেসির পাতা থেকেই প্রেসক্রিপশন পাঠানো -->
          <template v-if="kind === 'ambulance' && !((raw as Ambulance).busySince ?? 0)" #main>
            <Button icon="ambulance" data-testid="amb-request-one" @click="requestAmbulance({ id: raw.id, name: (raw as Ambulance).name })">{{ tr('Call ambulance', 'অ্যাম্বুলেন্স ডাকুন') }}</Button>
          </template>
          <template v-else-if="kind === 'pharmacy'" #main><RxSendFromPlace :pharmacy="raw as Pharmacy" :mode="buyMode ?? undefined" :rx="rx" :last="lastHome" /></template>
          <!-- ফার্মেসি: প্রেসক্রিপশনের হিসাব মাথার ঠিক নিচে (কার্ডের একই) -->
          <template v-if="kind === 'pharmacy'" #top><PharmacyRxInfo :p="raw as Pharmacy" :mode="buyMode" big /></template>
        </EntryDetail>
        <DetailPane v-else-if="area && (q.isLoading || q.isFetching)" :title="kindLabel(kind)" :back-to="listPath"><Loading /></DetailPane>
        <DetailPane v-else :title="kindLabel(kind)" :back-to="listPath"><EmptyState :icon="kindIcon(kind)" :title="tr('Not found', 'পাওয়া যায়নি')" /></DetailPane>
      </template>
    </template>
    <template #placeholder>
      <Summary :kind="kind" :area="area ? areaLabel(area.district, area.upazilaId) : ''" :items="items" :busy-count="busy.length" :loading="!!area && q.isLoading" />
    </template>
  </ListDetail>
  <AreaPicker v-if="showArea" :value="area ? { district: area.district, upazilaId: null } : null" @close="showArea = false" @picked="onPicked" />
  <FilterSheet v-if="showFilter" @close="showFilter = false" @clear="clearFilters">
    <div v-if="kind === 'ambulance'" class="col gap-6">
      <span class="t-title-s">{{ tr('Ambulance type', 'অ্যাম্বুলেন্সের ধরন') }}</span>
      <ChoiceChips :options="typeOpts" :model-value="type" @update:model-value="(v: string) => setParam('type', v === type ? null : v)" />
    </div>
    <Switch v-if="kind === 'pharmacy'" :label="tr('Open now only', 'শুধু এখন খোলা')" :model-value="openNow" @update:model-value="(v) => setParam('open', v ? '1' : null)" />
    <Switch v-if="kind === 'bloodbank' || kind === 'pharmacy'" :label="tr('Open 24 hours only', 'শুধু ২৪ ঘণ্টা খোলা')" :model-value="open24" @update:model-value="(v) => setParam('h24', v ? '1' : null)" />
  </FilterSheet>
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
  <ReportDialog v-if="reporting" :kind="kind" :id="reporting" @close="reporting = null" />
</template>
