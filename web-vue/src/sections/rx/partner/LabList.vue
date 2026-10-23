<script setup lang="ts">
// প্যাথলজি — অনুরোধের তালিকা (আমার কাজ CONTRACT §২): স্থির ভাগ নতুন n · চলছে n · শেষ (?s=), খোঁজা, প্রতিটায় একটাই মূল বোতাম
// (নমুনা নিয়েছি → রিপোর্ট দিন → দেখুন; বাকিটা "আরও"-তে)। কোড দিয়ে খোলা ফোনে উপরে, ডেস্কটপে (৯৬০px+) ডান ভাগে —
// সেখানে বাছা অনুরোধ (LabDetail) পাশেই খোলে, তালিকা বাঁয়ে থাকে।
import { computed, ref } from 'vue';
import { Button, ErrorBox, Icon, RowMenu, SkeletonCards, Tag } from '@/ui/kit';
import { call, errorText } from '@/lib/api';
import { tr } from '@/lib/i18n';
import { timeAgo } from '@/lib/format';
import { useNav, useSearchParams } from '@/lib/nav';
import { useInvalidate } from './common';
import { useTwoPane } from '@/shell/chrome';
import { LAB_SEGS, ageText, genderText, labSegOf, labStatusText, labTone, segCounts, type LabOrder, type LabOrders, type LabSeg } from './model';
import CodeBox from './CodeBox.vue';
import BizPreview from '../../work/BizPreview.vue';
import QueueSegs, { type QSeg } from '@/ui/listing/QueueSegs.vue';
import ModeTag from './ModeTag.vue';
import HomeLabTag from './HomeLabTag.vue';
import LabHomeContact from './LabHomeContact.vue';
import LabDetail from './LabDetail.vue';

const BASE = '/work/lab/orders';
/** পুরোনো লিংকের ?v= (চালু/রিপোর্ট/বাতিল/সব) → নতুন ভাগ */
const LEGACY_VIEW: Record<string, LabSeg> = { open: 'new', reported: 'reported', cancelled: 'closed', all: 'new' };

const props = defineProps<{ orders: { data?: LabOrders; isLoading: boolean; error: Error | null; refetch: () => unknown }; detailId?: string | null }>();
const nav = useNav();
const two = useTwoPane();
const invalidate = useInvalidate();
const [sp, setSp] = useSearchParams();
const q = ref('');
const busyId = ref<string | null>(null);
const opening = ref(false);
const err = ref<string | null>(null);
const openErr = ref<string | null>(null);
const items = computed(() => props.orders.data?.items ?? []);
const counts = computed(() => segCounts(LAB_SEGS, props.orders.data?.counts, labSegOf as (x: never) => LabSeg, items.value));
// ভাগ: নতুন / চলছে / শেষ (সেবার "আমার কাজ"-এর এক ছাঁচ, QueueSegs) — সার্ভারের new · active · (reported + closed = শেষ)
const view = computed<QSeg>(() => {
  const asked = (sp.value.get('s') ?? LEGACY_VIEW[sp.value.get('v') ?? '']) as string | null;
  if (!asked && props.detailId) {
    // খোলা অনুরোধের ভাগই (ডেস্কটপে তালিকা পাশে থাকে)
    const o = items.value.find((x) => x.id === props.detailId);
    if (o) return segOf(o);
  }
  return asked === 'active' ? 'active' : asked === 'done' || asked === 'reported' || asked === 'closed' ? 'done' : 'new';
});
const segOf = (o: LabOrder): QSeg => {
  const x = labSegOf(o);
  return x === 'new' ? 'new' : x === 'active' ? 'active' : 'done';
};
const t = computed(() => q.value.trim().toLowerCase());
const rows = computed(() => {
  const inSeg = items.value.filter((o) => segOf(o) === view.value).sort((a, c) => c.createdAt - a.createdAt);
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
const keep = computed(() => (view.value === 'new' ? '' : `?s=${view.value}`));
const go = (o: LabOrder) => nav(`${BASE}/${o.id}${keep.value}`);
// ল্যাবে আসবেন / বাসা থেকে নমুনা
const fromHome = (o: LabOrder) => !!o.home && o.home.status !== 'declined';
const who = (o: LabOrder) => [ageText(o.patientAge), genderText(o.patientGender)].filter(Boolean).join(' · ');
const onKey = (e: KeyboardEvent, o: LabOrder) => {
  if (e.key === 'Enter' && e.target === e.currentTarget) go(o);
};
</script>

<template>
  <div class="qd">
    <div class="qd-list">
      <div class="row wrap gap-8">
        <QueueSegs
          :label="tr('Test requests', 'পরীক্ষার অনুরোধ')"
          :value="view"
          :counts="{ new: counts.new, active: counts.active }"
          @change="(k) => setSp(k === 'new' ? {} : { s: k }, { replace: true })"
        />
        <div class="rxp-search rxp-search-sm grow">
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
                  : tr('No reports given yet', 'এখনো কোনো রিপোর্ট দেওয়া হয়নি')
          }}
        </span>
        <span v-if="!t && view === 'new'" class="t-body-s">{{ tr('Tests patients send from their prescriptions come here.', 'রোগীরা প্রেসক্রিপশন থেকে পরীক্ষা পাঠালে এখানে আসবে।') }}</span>
        <span v-if="!t && view === 'active'" class="t-body-s">{{ tr('After you take a sample (or confirm a home visit), it waits here for the report.', 'নমুনা নিলে (বা বাসায় যাওয়ার সময় নিশ্চিত করলে) রিপোর্ট দেওয়া পর্যন্ত এখানে থাকবে।') }}</span>
      </div>
      <ul v-else-if="two" class="q-list" :aria-label="tr('Test requests', 'পরীক্ষার অনুরোধ')">
        <li v-for="o in rows" :key="o.id">
          <button type="button" class="q-item" :aria-current="o.id === detailId" data-testid="q-item" @click="go(o)">
            <span class="col gap-2 grow" :style="{ minWidth: 0 }">
              <span class="t-title-s ellipsis">{{ o.patientName || '—' }}</span>
              <span class="t-body-s muted ellipsis">{{ o.tests.join(', ') }}</span>
              <span class="t-body-s muted">{{ [who(o), timeAgo(o.createdAt)].filter(Boolean).join(' · ') }}</span>
              <HomeLabTag :o="o" />
            </span>
            <Tag v-if="o.status !== 'sent'" :tone="labTone(o.status)">{{ labStatusText(o.status) }}</Tag>
          </button>
        </li>
      </ul>
      <div v-else class="rxp-cards">
        <article v-for="o in rows" :key="o.id" class="card click col gap-6" role="button" tabindex="0" data-testid="lab-card" @click="go(o)" @keydown="onKey($event, o)">
          <div class="row gap-8">
            <span class="t-title-s grow">{{ o.patientName || '—' }}</span>
            <ModeTag :remote="fromHome(o)" :icon="fromHome(o) ? 'home_health' : 'local_hospital'">{{ fromHome(o) ? tr('Sample from home', 'বাসা থেকে নমুনা') : tr('Will come to the lab', 'ল্যাবে আসবেন') }}</ModeTag>
          </div>
          <div class="row wrap gap-6">
            <Tag v-if="o.status !== 'sent'" :tone="labTone(o.status)">{{ labStatusText(o.status) }}</Tag>
            <span class="t-body-s muted">{{ [who(o), timeAgo(o.createdAt)].filter(Boolean).join(' · ') }}</span>
          </div>
          <span class="t-body-m">{{ o.tests.join(', ') }}</span>
          <HomeLabTag :o="o" />
          <LabHomeContact :o="o" />
          <span v-if="o.doctorName" class="t-body-s muted">{{ o.doctorName }}</span>
          <!-- একটাই মূল বোতাম: নমুনা নিয়েছি → রিপোর্ট দিন → দেখুন -->
          <div class="q-acts rxp-row-actions" @click.stop>
            <Button v-if="o.status === 'sent'" icon="check_circle" :busy="busyId === o.id" :disabled="!!busyId" @click="sampleTaken(o)">{{ tr('Sample taken', 'নমুনা নিয়েছি') }}</Button>
            <Button v-else-if="o.status === 'received'" icon="edit_note" @click="go(o)">{{ tr('Give report', 'রিপোর্ট দিন') }}</Button>
            <Button v-else-if="o.status === 'reported'" variant="tonal" @click="go(o)">{{ tr('View', 'দেখুন') }}</Button>
            <span class="grow" />
            <RowMenu :items="[o.status === 'sent' && { label: tr('Give report', 'রিপোর্ট দিন'), icon: 'edit_note', onClick: () => go(o) }]" />
          </div>
        </article>
      </div>
      <BizPreview kind="lab" />
    </div>
    <aside class="qd-side rxp-codeside" :aria-label="tr('Details', 'বিস্তারিত')">
      <section class="card rxp-panel rxp-lab-code rxp-open-code">
        <div class="row gap-8">
          <span class="rxp-panel-ic"><Icon name="search" /></span>
          <h2 class="t-title-m grow">{{ tr('Open a prescription by code', 'কোড দিয়ে প্রেসক্রিপশন খুলুন') }}</h2>
        </div>
        <CodeBox :label="tr('Patient came with a paper prescription? Code under the QR (or paste the link)', 'রোগী কাগজের প্রেসক্রিপশন নিয়ে এলে: QR-এর নিচের কোড (বা লিংক পেস্ট করুন)')" :busy="opening" :error="openErr" @open="openCode" />
        <p class="t-body-s muted">{{ tr('The tests are added here and the report form opens.', 'পরীক্ষাগুলো এখানে যোগ হবে, রিপোর্টের পাতা খুলবে।') }}</p>
      </section>
      <LabDetail v-if="two && detailId" :key="detailId" :id="detailId" :orders="orders.data?.items ?? null" :orders-loading="orders.isLoading" in-pane />
    </aside>
  </div>
</template>
