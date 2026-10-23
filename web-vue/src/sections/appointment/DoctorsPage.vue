<script setup lang="ts">
// ডাক্তার / পুষ্টিবিদ / ফিটনেস ট্রেইনার … — সেবার পাঁচ তালিকার এক ছাঁচে (src/ui/listing, listings CONTRACT §১): "সেবা"-র ভেতরে
// (/services/doctor, base="services") আর "অ্যাপয়েন্টমেন্ট"-এ (/appointment) — একই পাতা। উপরে খোঁজা + এলাকা, সাজানো,
// এক লাইনের ছাঁকনির চিপ (চিকিৎসা পদ্ধতি, আজ/আগামীকাল, অনলাইন, এখন চেম্বারে, ফি, বিশেষজ্ঞতা) আর "আরও" (বাকি ছাঁকনির শিট)।
// কার্ড: বিশেষজ্ঞতা · পদ্ধতি · ফি, "আজ সিরিয়াল আছে", রেটিং, "সিরিয়াল নিন" + যোগাযোগ। ডেস্কটপে ডানে বাছা (না হলে প্রথম) ডাক্তার।
import { computed, reactive, ref, watch } from 'vue';
import { RouterLink } from 'vue-router';
import { Button, ChoiceChips, ErrorBox, Icon, InfoText, PillTabs, Switch, toast } from '@/ui/kit';
import { useFilterArea } from '@/ui/areaFilter';
import DetailPane from '@/shell/DetailPane.vue';
import ListDetail from '@/shell/ListDetail.vue';
import { usePageTitle, useTwoPane } from '@/shell/chrome';
import { errorText } from '@/lib/api';
import { useCall } from '@/lib/query';
import { useSession } from '@/lib/session';
import { store } from '@/lib/store';
import { num, tr } from '@/lib/i18n';
import { areaLabel } from '@/lib/geo';
import { useNav, useSearchParams } from '@/lib/nav';
import ListingCard from '@/ui/listing/ListingCard.vue';
import ListingTop, { type FilterChip } from '@/ui/listing/ListingTop.vue';
import ListingSkeleton from '@/ui/listing/ListingSkeleton.vue';
import ListingEmpty from '@/ui/listing/ListingEmpty.vue';
import ListingDetail from '@/ui/listing/ListingDetail.vue';
import { LT, isSort, matchesSearch, sortListings, type SortKey } from '@/ui/listing/model';
import ServiceTabs from '@/sections/services/ServiceTabs.vue';
import { FEE_STEPS, badgeIcon, badgeLabel, chamberTypes, chamberLabel, parseProvider, specialtiesOf, specialtyLabel, useCatalog, type Hospital, type SpecialtyFacet } from './model';
import { NO_FILTERS, TAB_ICON, dayLabel, feeLabel, filterKey, genderLabel, loadFilters, tabLabel, useDebounced, type SavedFilters } from './doctors';
import { useAreasReady, type AreaSel } from './shared';
import { bookableOf, doctorListing } from './listing';
import AreaSheet from './AreaSheet.vue';
import FilterSheet from './FilterSheet.vue';
import HospitalPickerDialog from './HospitalPickerDialog.vue';
import SpecialtyPicker from './SpecialtyPicker.vue';
import ProviderDetail from './ProviderDetail.vue';
import BookDialog from './BookDialog.vue';
import { T as OT } from './online';
import { onlineOn } from './online/flags';
import { SYSTEMS, isSystem, newTypeEmpty, systemLabel, systemsOn } from '@/lib/practice';
import { TG, practitionerLabel, practitionerName, titleGuardOn } from '@/lib/titleGuard';

const props = withDefaults(defineProps<{ detailId: string | null; base?: 'appointment' | 'services' }>(), { base: 'appointment' });
usePageTitle(() => (props.base === 'services' ? tr('Services', 'সেবা') : tr('Appointment', 'অ্যাপয়েন্টমেন্ট')));
const two = useTwoPane();
useCatalog();
useAreasReady();
const nav = useNav();
const [sp, setSp] = useSearchParams();
const s = useSession();
const type = computed(() => (chamberTypes().includes(sp.value.get('t') ?? '') ? (sp.value.get('t') as string) : 'doctor'));
// চিকিৎসা পদ্ধতির ছাঁকনি (practice_systems, শুধু ডাক্তার) — শেষবারেরটা মনে থাকে; সুইচ বন্ধ থাকলে সার্ভারে যায় না
const SYS_KEY = 'lf.doctorSystem';
const sysSaved = ref<string | null>(((v) => (isSystem(v) ? v : null))(store.getJson<string>(SYS_KEY)));
const sysFilter = computed(() => (systemsOn() && type.value === 'doctor' ? sysSaved.value : null));
function setSys(v: string | null) {
  sysSaved.value = v;
  store.setJson(SYS_KEY, v);
}
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
  if (ff.online && onlineOn()) b.online = true;
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
  if (sysFilter.value) b.system = sysFilter.value;
  return b;
});
type Facets = { specialties: SpecialtyFacet[]; gender: boolean; systems?: { code: string; n: number }[] };
// সাজানো (URL-এ): জায়গা জানা থাকলে নিকটতম, নইলে রেটিং — সার্ভার সাজায় (sort), পুরোনো সার্ভারে এখানে
const located = computed(() => s.place?.source === 'gps' || cur.value.upazilaId != null);
const sort = computed<SortKey>(() => (isSort(sp.value.get('sort')) ? (sp.value.get('sort') as SortKey) : located.value ? 'near' : 'rating'));
const setSort = (k: SortKey) => {
  const n = new URLSearchParams(sp.value);
  n.set('sort', k);
  setSp(n, { replace: true });
};
const fullBody = computed(() => {
  const b: Record<string, unknown> = { ...body.value, sort: sort.value };
  if (s.place?.source === 'gps') Object.assign(b, { lat: s.place.lat, lng: s.place.lng });
  return b;
});
const q = useCall<{ items: any[]; facets?: Facets }>('providers', fullBody);
const list = computed(() => (q.data?.items ?? []).map((o) => ({ p: parseProvider(o), raw: o, matched: Array.isArray(o?.matched) ? (o.matched as number[]) : null })));
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
const cards = computed(() => list.value.filter(({ p }) => p.uid !== s.uid).map((x) => ({ ...x, l: doctorListing(x.p, x.raw) })));
const shown = computed(() => sortListings(cards.value.filter((x) => matchesSearch(x.l, query.value)).map((x) => Object.assign(x.l, { _x: x })), sort.value).map((l) => (l as any)._x as (typeof cards.value)[number]));

// বাছা: URL-এর ডাক্তার; ডেস্কটপে কিছু বাছা না থাকলে প্রথমজন। সরাসরি খোলা লিংক এখনকার তালিকায় না থাকলে পুরো দেশের তালিকা থেকে
const inList = computed(() => (props.detailId ? list.value.find(({ p }) => p.uid === props.detailId) ?? null : null));
const fb = useCall<{ items: any[] }>('providers', () => ({ type: type.value, homeVisit: false }), () => ({ enabled: !!props.detailId && !inList.value && !q.isLoading }));
const selected = computed(() => {
  if (props.detailId) {
    if (inList.value) return inList.value;
    const o = (fb.data?.items ?? []).find((x) => x?.uid === props.detailId);
    return o ? { p: parseProvider(o), raw: o, matched: null } : null;
  }
  return two.value ? shown.value[0] ?? null : null;
});
const selListing = computed(() => (selected.value ? doctorListing(selected.value.p, selected.value.raw) : null));
const detailRef = ref<InstanceType<typeof ProviderDetail> | null>(null);

const qs = computed(() => sp.value.toString());
const root = computed(() => (props.base === 'services' ? '/services/doctor' : '/appointment'));
const listPath = computed(() => root.value + (qs.value ? `?${qs.value}` : ''));
const open = (id: string) => nav(`${props.base === 'services' ? '/services/doctor' : '/appointment/doctor'}/${id}${qs.value ? `?${qs.value}` : ''}`);
const hospitalName = (h: Hospital) => chamberLabel({ hospitalName: h.nameEn, hospitalNameBn: h.nameBn });
// ---------- ছাঁকনির চিপ (এক লাইন) — চালুগুলো বাছা দেখায়; "আরও" = বাকি ছাঁকনির শিট ----------
const chips = computed<FilterChip[]>(() => {
  const ff = f.value;
  const out: FilterChip[] = [];
  if (systemsOn() && type.value === 'doctor') {
    for (const x of SYSTEMS) {
      const n = facets.value?.systems?.find((y) => y.code === x)?.n;
      out.push({ key: 'sys-' + x, label: systemLabel(x) + (n != null ? ` (${num(n)})` : ''), on: sysFilter.value === x, toggle: () => setSys(sysFilter.value === x ? null : x) });
    }
  }
  out.push({ key: 'today', label: dayLabel('today'), on: ff.day === 'today', toggle: () => setF({ day: ff.day === 'today' ? null : 'today' }) });
  out.push({ key: 'tomorrow', label: dayLabel('tomorrow'), on: ff.day === 'tomorrow', toggle: () => setF({ day: ff.day === 'tomorrow' ? null : 'tomorrow' }) });
  if (type.value === 'doctor' && onlineOn()) out.push({ key: 'online', label: OT.online(), on: ff.online, toggle: () => setF({ online: !ff.online }) });
  out.push({ key: 'now', label: tr('In chamber now', 'এখন চেম্বারে'), on: ff.openNow, toggle: () => setF({ openNow: !ff.openNow }) });
  out.push({ key: 'fee', label: feeLabel(ff.maxFee && ff.maxFee !== 500 ? ff.maxFee : 500), on: !!ff.maxFee, toggle: () => setF({ maxFee: ff.maxFee ? null : 500 }) });
  const top = facets.value ? facets.value.specialties.slice(0, 3).map((x) => x.code) : [];
  if (ff.specialty && !top.includes(ff.specialty)) top.unshift(ff.specialty);
  for (const c of top) out.push({ key: 'sp-' + c, label: specialtyLabel(c), on: ff.specialty === c, toggle: () => setF({ specialty: ff.specialty === c ? null : c }) });
  return out;
});
// শিটের বাকি ছাঁকনি (চিপে নেই): হাসপাতাল, হাসপাতালের নাম, লিঙ্গ, বাসায় যান
const moreCount = computed(() => [f.value.hospital, f.value.hospitalQ.trim(), f.value.gender, f.value.homeOnly].filter(Boolean).length);
const anyFilter = computed(() => chips.value.some((c) => c.on) || moreCount.value > 0 || !!query.value.trim());
function clearAll() {
  clearF();
  setSys(null);
  query.value = '';
}
const barLabel = computed(() => {
  const h = f.value.hospital;
  const c = cur.value;
  if (f.value.online && onlineOn()) return tr('All districts', 'সব জেলা');
  return h ? areaLabel(h.district, h.upazilaId) : c.district ? areaLabel(c.district, c.upazilaId, c.unionId) : tr('All districts', 'সব জেলা');
});
const spOptions = computed(() => (facets.value ? facets.value.specialties.map((x) => x.code) : specialtiesOf(type.value)));
const spCount = (code: string) => facets.value?.specialties.find((x) => x.code === code)?.n;
// title_guard: হোমিও/ইউনানি/আয়ুর্বেদ বাছাই করা থাকলে "ডাক্তার" নয় — "হোমিওপ্যাথিক চিকিৎসক" …
const practWord = computed(() => (type.value === 'doctor' && sysFilter.value && sysFilter.value !== 'allopathy' && titleGuardOn() ? practitionerName(sysFilter.value) : null));
const genderText = (g: string) => (practWord.value ? (g === 'female' ? TG.female() : TG.male()) : genderLabel(g));
const emptyTitle = computed(() =>
  (practWord.value ? TG.noneFound(practWord.value) : null) ??
  newTypeEmpty(type.value) ??
  (type.value === 'nutritionist' ? tr('No nutritionists found', 'কোনো পুষ্টিবিদ পাওয়া যায়নি') : type.value === 'trainer' ? tr('No fitness trainers found', 'কোনো ফিটনেস ট্রেইনার পাওয়া যায়নি') : LT.empty('doctor')),
);

// ---------- "সিরিয়াল নিন" ----------
const booking = ref<{ p: (typeof list.value)[number]['p']; i: number } | null>(null);
function bookFromCard(x: (typeof cards.value)[number]) {
  const b = bookableOf(x.p);
  const i = x.l.slotChamber != null && b.includes(x.l.slotChamber) ? x.l.slotChamber : b.length === 1 ? b[0] : null;
  if (i == null) return open(x.p.uid);
  s.requireLogin(tr('Log in to request an appointment', 'অ্যাপয়েন্টমেন্ট চাইতে লগইন করুন'), () => (booking.value = { p: x.p, i }));
}
function booked() {
  booking.value = null;
  const m = tr("Request sent. You'll be notified when it's confirmed.", 'অনুরোধ পাঠানো হয়েছে। নিশ্চিত হলে নোটিফিকেশন পাবেন।');
  info.value = m;
  toast(m);
}
const detailFacts = computed<[string, string][]>(() => {
  const p = selected.value?.p;
  if (!p) return [];
  return [['school', p.degrees], ['badge', practitionerLabel(p) ?? badgeLabel(p.type)], ['local_hospital', p.chambers.length > 1 ? tr(`${p.chambers.length} chambers`, `${num(p.chambers.length)}টা চেম্বার`) : '']];
});

function changeTab(k: string) {
  // ধরনপ্রতি নিজের শেষ ছাঁকনি (store-এ) — বদলালে সেটা ফিরে আসে
  setSp(k === 'doctor' ? {} : { t: k }, { replace: true });
  if (props.detailId) nav(k === 'doctor' ? root.value : `${root.value}?t=${k}`, { replace: true });
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
  <ListDetail :has-detail="!!detailId" :auto="two">
    <div class="list-inner lst-page appt-pre">
      <ServiceTabs v-if="base === 'services'" kind="doctor" />
      <div v-else class="row appt-title">
        <h1 class="t-headline grow appt-h1">{{ tr('Appointment', 'অ্যাপয়েন্টমেন্ট') }}</h1>
        <div class="row appt-title-end">
          <RouterLink v-if="!s.guest" class="btn text small has-icon appt-my-link" to="/my-appointments" :aria-label="tr('My appointments', 'আমার অ্যাপয়েন্টমেন্ট')" :title="tr('My appointments', 'আমার অ্যাপয়েন্টমেন্ট')">
            <Icon name="event_outline" />
            <span class="appt-wide-only">{{ tr('My appointments', 'আমার অ্যাপয়েন্টমেন্ট') }}</span>
          </RouterLink>
          <RouterLink class="appt-home-btn" to="/appointment/home-care"><Icon name="home_health" />{{ tr('Home care', 'বাসায় সেবা') }}</RouterLink>
        </div>
      </div>
      <div v-if="chamberTypes().length > 1" class="lst-tabs">
        <PillTabs secondary :label="tr('Type', 'ধরন')" :tabs="chamberTypes().map((k) => ({ key: k, label: tabLabel(k), icon: TAB_ICON[k] }))" :value="type" @change="changeTab" />
      </div>
      <ListingTop
        v-model:search="query"
        kind="doctor"
        :area-label="barLabel"
        :on-area="() => (showArea = true)"
        :sort="sort"
        :sorts="['near', 'rating', 'open']"
        :chips="chips"
        :on-clear="clearAll"
        :on-more="() => (showFilter = true)"
        :more-count="moreCount"
        @sort="setSort"
      />
      <InfoText :text="info" />
      <ListingSkeleton v-if="q.isLoading" :n="4" />
      <ErrorBox v-else-if="q.error" :error="errorText(q.error)" :on-retry="() => q.refetch()" />
      <ListingEmpty v-else-if="!shown.length" :icon="badgeIcon(type)" :title="anyFilter && !practWord ? LT.noMatch() : emptyTitle" :on-clear="anyFilter ? clearAll : undefined" :on-area="() => (showArea = true)" />
      <div v-else class="lst-list">
        <ListingCard
          v-for="x in shown"
          :key="x.p.uid"
          :l="x.l"
          :selected="x.p.uid === selected?.p.uid"
          :on-open="() => open(x.p.uid)"
          :no-primary="!bookableOf(x.p).length"
        >
          <template #primary>
            <Button square icon="calendar_month" data-testid="book-serial" @click="bookFromCard(x)">{{ LT.book() }}</Button>
          </template>
        </ListingCard>
      </div>
    </div>
    <template #detail>
      <ListingDetail
        v-if="selected && selListing"
        :key="selected.p.uid"
        :l="selListing"
        :title="selected.p.name"
        :back-to="listPath"
        :facts="detailFacts"
        :no-primary="!bookableOf(selected.p).length"
        :on-report="!s.guest && selected.p.uid !== s.uid ? () => detailRef?.askReport() : undefined"
        :closable="!!detailId"
        @close="nav(listPath)"
      >
        <template #type>
          <ProviderDetail ref="detailRef" :p="selected.p" embedded :on-booked="(m: string) => (info = m)" />
        </template>
        <template #primary>
          <Button icon="calendar_month" data-testid="book-serial" @click="detailRef?.mainBook()">{{ LT.book() }}</Button>
        </template>
      </ListingDetail>
      <DetailPane v-else-if="detailId" :title="tr('Doctor', 'ডাক্তার')" :back-to="listPath">
        <ListingSkeleton v-if="fb.isLoading || q.isLoading" :n="1" />
        <ListingEmpty v-else icon="stethoscope" :title="tr('Not found', 'পাওয়া যায়নি')" />
      </DetailPane>
      <DetailPane v-else :title="tabLabel(type)" :back-to="listPath">
        <ListingSkeleton v-if="q.isLoading" :n="1" />
        <ListingEmpty v-else :icon="badgeIcon(type)" :title="emptyTitle" :on-area="() => (showArea = true)" />
      </DetailPane>
    </template>
  </ListDetail>
  <AreaSheet
    v-if="showArea"
    :value="f.hospital ? { district: f.hospital.district, upazilaId: null, unionId: null } : cur"
    allow-all
    with-unions
    @pick="areaPicked"
    @close="showArea = false"
  />
  <FilterSheet v-if="showFilter" :title="tr('Filter', 'ফিল্টার')" :on-clear="anyFilter ? clearAll : undefined" @close="showFilter = false">
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
      <span class="t-title-s">{{ practWord ?? tr('Doctor', 'ডাক্তার') }}</span>
      <ChoiceChips
        :options="[{ value: '', label: tr('Any', 'যেকোনো') }, { value: 'female', label: genderText('female') }, { value: 'male', label: genderText('male') }]"
        :model-value="f.gender ?? ''"
        @update:model-value="(v: string) => setF({ gender: v === 'male' || v === 'female' ? v : null })"
      />
    </div>
    <Switch v-if="type === 'doctor'" :label="tr('Home visit', 'বাসায় যান')" :model-value="f.homeOnly" @update:model-value="(v) => setF({ homeOnly: v })" />
    <Switch v-if="type === 'doctor' && onlineOn()" :label="OT.modeLabel('online')" :sub="OT.filterSub()" data-testid="filter-online" :model-value="f.online" @update:model-value="(v) => setF({ online: v })" />
  </FilterSheet>
  <BookDialog v-if="booking" :provider="booking.p" :index="booking.i" @close="booking = null" @booked="booked" />
  <HospitalPickerDialog v-if="pickHospital" :initial-district="cur.district" :initial-upazila-id="cur.upazilaId" @pick="hospitalPicked" @close="pickHospital = false" />
</template>
