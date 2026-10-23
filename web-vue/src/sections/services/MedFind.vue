<script setup lang="ts">
// "ওষুধ খুঁজুন" (অ্যাপের MedFind.kt): তালিকা থেকে ওষুধ বেছে, কতটা লাগবে বলে, কাছের দোকানগুলোকে জিজ্ঞেস করা;
// যে দোকান "আছে" (পুরোটা বা কিছু) বলে, সেখানে সরাসরি ফোন। দাম, অর্ডার কিছুই অ্যাপে নেই। backend/docs/MEDFIND.md
import { computed, onMounted, ref, watch } from 'vue';
import { Button, EmptyState, ErrorText, Icon, InfoText, SkeletonCards } from '@/ui/kit';
import AreaPicker from '@/ui/AreaPicker.vue';
import { useFilterArea, type AreaValue } from '@/ui/areaFilter';
import ListDetail from '@/shell/ListDetail.vue';
import { useTwoPane } from '@/shell/chrome';
import { call, errorText } from '@/lib/api';
import { useCall } from '@/lib/query';
import { num, tr } from '@/lib/i18n';
import { areaLabel, ensureAreas } from '@/lib/geo';
import { useSession } from '@/lib/session';
import { useSearchParams } from '@/lib/nav';
import { drugDetail, drugTitle, unitsFor, type Drug, type MyFind, type QtyUnit } from './medfind';
import QtyPicker from './QtyPicker.vue';
import ServiceTabs from './ServiceTabs.vue';
import ReportDialog from './ReportDialog.vue';
import MyFindCard from './MyFindCard.vue';

const s = useSession();
const two = useTwoPane();
onMounted(() => ensureAreas().catch(() => {}));
const [params] = useSearchParams();
const query = ref((params.value.get('q') ?? '').slice(0, 40));
const dq = ref(query.value.trim());
const chosen = ref<Drug | null>(null);
// কতটা লাগবে — ওষুধ বাছলে ১ আর ধরনের সবচেয়ে স্বাভাবিক একক (ট্যাবলেট: ১ পাতা)
const qty = ref(1);
const unit = ref<QtyUnit>('unit');
const [area, setArea] = useFilterArea();
const showArea = ref(false);
const busy = ref(false);
const err = ref<string | null>(null);
const info = ref<string | null>(null);
const reporting = ref<string | null>(null);

// লেখা থামলে তবেই সার্ভারে খোঁজা
watch(query, (qv, _o, onCleanup) => {
  const t = setTimeout(() => (dq.value = qv.trim()), 350);
  onCleanup(() => clearTimeout(t));
});
const found = useCall<{ items: Drug[] }>('drug_search', () => ({ q: dq.value }), () => ({ enabled: dq.value.length >= 2, staleTime: 10 * 60_000 }));
const results = computed(() => (dq.value.length >= 2 ? found.data?.items ?? [] : []));
const searching = computed(() => query.value.trim().length >= 2 && (found.isFetching || dq.value !== query.value.trim()));

const mine = useCall<{ items: MyFind[] }>('med_finds_mine', {}, () => ({ enabled: !s.guest }));

function ask() {
  const c = chosen.value;
  if (!c) return;
  const a = area.value;
  if (!a) {
    err.value = tr('Choose a district', 'জেলা বেছে নিন');
    return;
  }
  const gps = s.place?.source === 'gps' ? s.place : null;
  s.requireLogin(tr('Log in to ask pharmacies', 'দোকানে জিজ্ঞেস করতে লগইন করুন'), async () => {
    busy.value = true;
    err.value = null;
    info.value = null;
    try {
      const r = await call<{ ok?: boolean; noPharmacy?: boolean; noneOpen?: boolean; notified?: number }>('med_find', {
        i: c.i, district: a.district, upazilaId: a.upazilaId ?? null, lat: gps?.lat ?? null, lng: gps?.lng ?? null,
        qty: qty.value, unit: unit.value,
      });
      if (r.noPharmacy) err.value = tr('No pharmacy in this district has joined the app yet.', 'এই জেলায় এখনো অ্যাপে কোনো ফার্মেসি যোগ হয়নি।');
      else if (r.noneOpen) err.value = tr('No pharmacy nearby can take a question right now (closed or busy). Please try again later.', 'এই মুহূর্তে কাছের কোনো ফার্মেসি খোলা বা জিজ্ঞাসা নেওয়ার মতো নেই। একটু পরে আবার চেষ্টা করুন।');
      else {
        const n = r.notified ?? 0;
        info.value = tr(`Asked ${n} nearby pharmacies. Answers will appear below, with a notification.`, `কাছের ${num(n)}টা দোকানকে জিজ্ঞেস করা হয়েছে। উত্তর নিচে আসবে, নোটিফিকেশনও পাবেন।`);
        chosen.value = null;
        query.value = '';
        mine.refetch();
      }
    } catch (e) {
      err.value = errorText(e);
    } finally {
      busy.value = false;
    }
  });
}

async function close(id: string) {
  try {
    await call('med_find_close', { id });
    mine.refetch();
  } catch (e) {
    err.value = errorText(e);
  }
}
function choose(d: Drug) {
  chosen.value = d;
  qty.value = 1;
  unit.value = unitsFor(d.form)[0];
  err.value = null;
  info.value = null;
}
function onPicked(v: AreaValue) {
  setArea({ district: v.district, upazilaId: v.upazilaId ?? null, unionId: null });
  err.value = null;
  showArea.value = false;
}
const finds = computed(() => mine.data?.items ?? []);
const showSearches = computed(() => !s.guest && (mine.isLoading || finds.value.length > 0));
</script>

<template>
  <ListDetail :has-detail="false">
    <div class="list-inner">
      <ServiceTabs kind="pharmacy" sub="medicine" />
      <div v-if="chosen" class="card col gap-12">
        <div class="row">
          <div class="col gap-2 grow">
            <span class="t-title-m">{{ chosen.name }}</span>
            <span class="t-body-s muted">{{ drugDetail(chosen) }}</span>
          </div>
          <Button variant="text" @click="chosen = null">{{ tr('Change', 'বদলান') }}</Button>
        </div>
        <QtyPicker v-model:qty="qty" v-model:unit="unit" :form="chosen.form" />
        <span class="t-title-s">{{ tr('Where', 'কোথায়') }}</span>
        <button type="button" class="area-btn" @click="showArea = true">
          <Icon name="location_on" />
          <span>{{ area ? areaLabel(area.district, area.upazilaId) : tr('Choose area', 'এলাকা বেছে নিন') }}</span>
          <Icon name="expand_more_round" />
        </button>
        <p class="t-body-s muted">{{ tr("If it's a prescription medicine, the pharmacy will need to see the prescription.", 'প্রেসক্রিপশনের ওষুধ হলে কিনতে দোকানে প্রেসক্রিপশন দেখাতে হবে।') }}</p>
        <ErrorText :text="err" />
        <Button block icon="send" :busy="busy" @click="ask">{{ tr('Ask nearby pharmacies', 'কাছের দোকানগুলোকে জিজ্ঞেস করুন') }}</Button>
      </div>
      <div v-else class="col gap-8">
        <label class="services-search">
          <Icon name="search" />
          <span class="sr-only">{{ tr('Medicine name', 'ওষুধের নাম') }}</span>
          <input v-model="query" class="input" maxlength="40" autocomplete="off" :placeholder="tr('e.g. Napa Extend, Seclo, Esomeprazole', 'যেমন: Napa Extend, Seclo, Esomeprazole')" />
        </label>
        <div v-if="results.length" class="col gap-6">
          <button v-for="d in results" :key="d.i" type="button" class="services-drug" @click="choose(d)">
            <span class="t-title-s">{{ drugTitle(d) }}</span>
            <span class="t-body-s muted">{{ [d.generic, d.company].filter(Boolean).join(' · ') }}</span>
          </button>
        </div>
        <p v-if="searching" class="t-body-s muted">{{ tr('Searching…', 'খোঁজা হচ্ছে…') }}</p>
        <p v-else-if="query.trim().length >= 2 && !results.length && found.isFetched" class="t-body-s muted">{{ tr('Not in the list — call a nearby pharmacy and ask.', 'তালিকায় নেই — কাছের ফার্মেসিতে ফোন করে জিজ্ঞেস করুন।') }}</p>
      </div>
      <ErrorText v-if="!chosen" :text="err" />
      <InfoText :text="info" />
      <template v-if="!two && !s.guest">
        <SkeletonCards v-if="mine.isLoading" :n="2" :h="110" />
        <div v-else-if="finds.length" class="col gap-12">
          <div class="row">
            <span class="t-title-s grow">{{ tr('My searches', 'আমার খোঁজ') }}</span>
            <Button variant="text" :busy="mine.isFetching" @click="mine.refetch()">{{ tr('Refresh', 'রিফ্রেশ') }}</Button>
          </div>
          <MyFindCard v-for="f in finds" :key="f.id" :f="f" :guest="s.guest" @report="(id) => (reporting = id)" @close="close(f.id)" />
        </div>
      </template>
    </div>
    <template #placeholder>
      <div class="col gap-12">
        <template v-if="showSearches">
          <SkeletonCards v-if="mine.isLoading" :n="2" :h="110" />
          <div v-else class="col gap-12">
            <div class="row">
              <span class="t-title-s grow">{{ tr('My searches', 'আমার খোঁজ') }}</span>
              <Button variant="text" :busy="mine.isFetching" @click="mine.refetch()">{{ tr('Refresh', 'রিফ্রেশ') }}</Button>
            </div>
            <MyFindCard v-for="f in finds" :key="f.id" :f="f" :guest="s.guest" @report="(id) => (reporting = id)" @close="close(f.id)" />
          </div>
        </template>
        <EmptyState v-else icon="medication" :title="tr('My searches', 'আমার খোঁজ')">
          <Button v-if="s.guest" variant="tonal" @click="s.login()">{{ tr('Log in', 'লগইন') }}</Button>
        </EmptyState>
      </div>
    </template>
  </ListDetail>
  <AreaPicker v-if="showArea" :value="area ? { district: area.district, upazilaId: null } : null" allow-all-district @close="showArea = false" @picked="onPicked" />
  <ReportDialog v-if="reporting" kind="pharmacy" :id="reporting" @close="reporting = null" />
</template>
