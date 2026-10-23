<script setup lang="ts">
// প্যাথলজি — অনুরোধের তালিকা: কোড দিয়ে খোলা, নতুন · চলমান · রিপোর্ট · বাতিল (?s=), খোঁজা, নমুনা নেওয়া
import { computed, ref } from 'vue';
import { Button, ErrorBox, Icon, PillTabs, SkeletonCards, Tag, cx } from '@/ui/kit';
import { call, errorText } from '@/lib/api';
import { tr } from '@/lib/i18n';
import { formatDateTime, timeAgo } from '@/lib/format';
import { useNav, useSearchParams } from '@/lib/nav';
import { useInvalidate, useWide } from './common';
import { LAB_SEGS, ageText, genderText, labSegLabel, labSegOf, labStatusText, labTone, segCounts, type LabOrder, type LabOrders, type LabSeg } from './model';
import CodeBox from './CodeBox.vue';
import ModeTag from './ModeTag.vue';
import HomeLabTag from './HomeLabTag.vue';
import LabHomeContact from './LabHomeContact.vue';

const BASE = '/work/lab/orders';
/** পুরোনো লিংকের ?v= (চালু/রিপোর্ট/বাতিল/সব) → নতুন ভাগ */
const LEGACY_VIEW: Record<string, LabSeg> = { open: 'new', reported: 'reported', cancelled: 'closed', all: 'new' };

const props = defineProps<{ orders: { data?: LabOrders; isLoading: boolean; error: Error | null; refetch: () => unknown } }>();
const nav = useNav();
const wide = useWide();
const invalidate = useInvalidate();
const [sp, setSp] = useSearchParams();
const q = ref('');
const busyId = ref<string | null>(null);
const opening = ref(false);
const err = ref<string | null>(null);
const openErr = ref<string | null>(null);
const items = computed(() => props.orders.data?.items ?? []);
const counts = computed(() => segCounts(LAB_SEGS, props.orders.data?.counts, labSegOf as (x: never) => LabSeg, items.value));
const view = computed<LabSeg>(() => {
  const asked = (sp.value.get('s') ?? LEGACY_VIEW[sp.value.get('v') ?? '']) as LabSeg | null;
  return asked && LAB_SEGS.includes(asked) ? asked : 'new';
});
const t = computed(() => q.value.trim().toLowerCase());
const rows = computed(() => {
  const inSeg = items.value.filter((o) => labSegOf(o) === view.value).sort((a, c) => c.createdAt - a.createdAt);
  const s = t.value;
  return s ? inSeg.filter((o) => o.patientName.toLowerCase().includes(s) || o.code.toLowerCase().includes(s) || o.tests.some((x) => x.toLowerCase().includes(s))) : inSeg;
});

async function openCode(code: string) {
  opening.value = true;
  openErr.value = null;
  try {
    const r = await call<{ item: LabOrder }>('lab_open_code', { code });
    await invalidate('lab_orders');
    nav(`${BASE}/${r.item.id}`);
  } catch (e) {
    openErr.value = errorText(e);
  } finally {
    opening.value = false;
  }
}
async function sampleTaken(o: LabOrder) {
  busyId.value = o.id;
  err.value = null;
  try {
    await call('lab_order_status', { id: o.id, status: 'received' });
    await invalidate('lab_orders');
  } catch (e) {
    err.value = errorText(e);
  } finally {
    busyId.value = null;
  }
}
const go = (o: LabOrder) => nav(`${BASE}/${o.id}`);
// ল্যাবে আসবেন / বাসা থেকে নমুনা
const fromHome = (o: LabOrder) => !!o.home && o.home.status !== 'declined';
const who = (o: LabOrder) => [ageText(o.patientAge), genderText(o.patientGender)].filter(Boolean).join(' · ');
const onKey = (e: KeyboardEvent, o: LabOrder) => {
  if (e.key === 'Enter' && e.target === e.currentTarget) go(o);
};
</script>

<template>
  <div class="col gap-16 rxp-codewrap">
    <section class="card rxp-panel rxp-lab-code rxp-open-code">
      <div class="row gap-8">
        <span class="rxp-panel-ic"><Icon name="search" /></span>
        <h2 class="t-title-m grow">{{ tr('Open a prescription by code', 'কোড দিয়ে প্রেসক্রিপশন খুলুন') }}</h2>
      </div>
      <CodeBox :label="tr('Patient came with a paper prescription? Code under the QR (or paste the link)', 'রোগী কাগজের প্রেসক্রিপশন নিয়ে এলে: QR-এর নিচের কোড (বা লিংক পেস্ট করুন)')" :busy="opening" :error="openErr" @open="openCode" />
      <p class="t-body-s muted">{{ tr('The tests are added here and the report form opens.', 'পরীক্ষাগুলো এখানে যোগ হবে, রিপোর্টের পাতা খুলবে।') }}</p>
    </section>

    <div class="row wrap gap-8 rxp-tabsrow">
      <div class="rxp-sub-pills">
        <PillTabs
          secondary
          :label="tr('Test requests', 'পরীক্ষার অনুরোধ')"
          :value="view"
          :tabs="LAB_SEGS.map((s) => ({ key: s, label: labSegLabel(s), count: counts[s] }))"
          @change="(k) => setSp(k === 'new' ? {} : { s: k }, { replace: true })"
        />
      </div>
      <span class="grow" />
      <div class="rxp-search rxp-search-sm">
        <Icon name="search" />
        <input v-model="q" class="input" :placeholder="tr('Name, code or test', 'নাম, কোড বা পরীক্ষা')" :aria-label="tr('Search', 'খুঁজুন')" />
      </div>
    </div>
    <ErrorBox v-if="err" :error="err" />

    <SkeletonCards v-if="orders.isLoading" :n="4" :h="72" />
    <ErrorBox v-else-if="orders.error" :error="errorText(orders.error)" :on-retry="() => orders.refetch()" />
    <div v-else-if="!rows.length" class="card rxp-empty-card">
      <Icon name="list_alt" />
      <span class="t-title-s">
        {{
          t
            ? tr('Nothing matches', 'মেলে এমন কিছু নেই')
            : view === 'new'
              ? tr('No new requests now', 'এখন নতুন কোনো অনুরোধ নেই')
              : view === 'active'
                ? tr('No samples waiting', 'এখন কোনো নমুনা বা চলমান পরীক্ষা নেই')
                : view === 'reported'
                  ? tr('No reports given yet', 'এখনো কোনো রিপোর্ট দেওয়া হয়নি')
                  : tr('Nothing cancelled', 'বাতিল হওয়া কোনো অনুরোধ নেই')
        }}
      </span>
      <span v-if="!t && view === 'new'" class="t-body-s">{{ tr('Tests patients send from their prescriptions come here.', 'রোগীরা প্রেসক্রিপশন থেকে পরীক্ষা পাঠালে এখানে আসবে।') }}</span>
      <span v-if="!t && view === 'active'" class="t-body-s">{{ tr('After you take a sample (or confirm a home visit), it waits here for the report.', 'নমুনা নিলে (বা বাসায় যাওয়ার সময় নিশ্চিত করলে) রিপোর্ট দেওয়া পর্যন্ত এখানে থাকবে।') }}</span>
    </div>
    <div v-else-if="wide" class="table-wrap">
      <table class="table rxp-table">
        <thead>
          <tr>
            <th>{{ tr('Patient', 'রোগী') }}</th>
            <th>{{ tr('Tests', 'পরীক্ষা') }}</th>
            <th>{{ tr('Doctor', 'ডাক্তার') }}</th>
            <th>{{ tr('Status', 'অবস্থা') }}</th>
            <th>{{ tr('Came', 'এসেছে') }}</th>
            <th><span class="sr-only">{{ tr('Actions', 'কাজ') }}</span></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="o in rows" :key="o.id" class="rxp-click" tabindex="0" @click="go(o)" @keydown="onKey($event, o)">
            <td>
              <div class="col gap-2">
                <span class="semi">{{ o.patientName || '—' }}</span>
                <span class="t-body-s muted">{{ [who(o), o.code].filter(Boolean).join(' · ') }}</span>
              </div>
            </td>
            <td class="rxp-td-tests">
              <div class="col gap-4">
                <span>{{ o.tests.join(', ') }}</span>
                <ModeTag :remote="fromHome(o)" :icon="fromHome(o) ? 'home_health' : 'local_hospital'">{{ fromHome(o) ? tr('Sample from home', 'বাসা থেকে নমুনা') : tr('Will come to the lab', 'ল্যাবে আসবেন') }}</ModeTag>
                <HomeLabTag :o="o" />
                <LabHomeContact :o="o" />
              </div>
            </td>
            <td class="t-body-s">{{ o.doctorName || '—' }}</td>
            <td><Tag :tone="labTone(o.status)">{{ labStatusText(o.status) }}</Tag></td>
            <td class="t-body-s muted rxp-nowrap" :title="formatDateTime(o.createdAt)">{{ timeAgo(o.createdAt) }}</td>
            <td class="rxp-td-actions">
              <div class="row wrap gap-6 rxp-row-actions" @click.stop>
                <Button v-if="o.status === 'sent'" small variant="outline" :busy="busyId === o.id" :disabled="!!busyId" @click="sampleTaken(o)">{{ tr('Sample taken', 'নমুনা নিয়েছি') }}</Button>
                <Button v-if="o.status === 'sent' || o.status === 'received'" small @click="go(o)">{{ tr('Give report', 'রিপোর্ট দিন') }}</Button>
                <Button v-if="o.status === 'reported'" small variant="tonal" @click="go(o)">{{ tr('View', 'দেখুন') }}</Button>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <div v-else class="rxp-cards">
      <article v-for="o in rows" :key="o.id" :class="cx('card click col gap-6')" role="button" tabindex="0" @click="go(o)" @keydown="onKey($event, o)">
        <div class="row gap-8">
          <span class="t-title-s grow">{{ o.patientName || '—' }}</span>
          <ModeTag :remote="fromHome(o)" :icon="fromHome(o) ? 'home_health' : 'local_hospital'">{{ fromHome(o) ? tr('Sample from home', 'বাসা থেকে নমুনা') : tr('Will come to the lab', 'ল্যাবে আসবেন') }}</ModeTag>
        </div>
        <div class="row wrap gap-6">
          <Tag :tone="labTone(o.status)">{{ labStatusText(o.status) }}</Tag>
          <span class="t-body-s muted">{{ [who(o), timeAgo(o.createdAt)].filter(Boolean).join(' · ') }}</span>
        </div>
        <span class="t-body-m">{{ o.tests.join(', ') }}</span>
        <HomeLabTag :o="o" />
        <LabHomeContact :o="o" />
        <span v-if="o.doctorName" class="t-body-s muted">{{ o.doctorName }}</span>
        <div class="row wrap gap-6 rxp-row-actions" @click.stop>
          <Button v-if="o.status === 'sent'" small variant="outline" :busy="busyId === o.id" :disabled="!!busyId" @click="sampleTaken(o)">{{ tr('Sample taken', 'নমুনা নিয়েছি') }}</Button>
          <Button v-if="o.status === 'sent' || o.status === 'received'" small @click="go(o)">{{ tr('Give report', 'রিপোর্ট দিন') }}</Button>
          <Button v-if="o.status === 'reported'" small variant="tonal" @click="go(o)">{{ tr('View', 'দেখুন') }}</Button>
        </div>
      </article>
    </div>
  </div>
</template>
