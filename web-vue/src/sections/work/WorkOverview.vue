<script setup lang="ts">
// /work — "আমার কাজ": প্রতিটা ব্যবসার অবস্থা, হিসাব আর দ্রুত কাজ (অ্যাপের WorkTab)
import { computed } from 'vue';
import { RouterLink } from 'vue-router';
import { Button, Icon } from '@/ui/kit';
import Workspace from '@/shell/Workspace.vue';
import { useSession } from '@/lib/session';
import { useCall } from '@/lib/query';
import { num, tr } from '@/lib/i18n';
import { formatDate } from '@/lib/format';
import { useNav } from '@/lib/nav';
import {
  ambulanceDocs, badgeIcon, badgeLabel, bloodBankDocs, businessName, labDocs, pharmacyDocs, statusCounts, useAfterChange, MAX_AMBULANCES, MAX_BLOODBANKS,
  type Ambulance, type BloodBank, type CallSummary, type Lab, type ListingStats, type Pharmacy,
} from './shared';
import WorkGate from './WorkGate.vue';
import WorkEmpty from './WorkEmpty.vue';
import Stat from './Stat.vue';
import StatusTag from './StatusTag.vue';
import DocsNeededNotice from './DocsNeededNotice.vue';
import BizCard from './BizCard.vue';
import { T as DT } from '@/offline/text';
import { invFeature } from '../invoices/features';
import { isDesktop } from '../invoices/offline';
import { writesRx } from '@/lib/practice';

const s = useSession();
const nav = useNav();
const amb = computed(() => s.work.ambulances as Ambulance[]);
const ph = computed(() => s.work.pharmacy as Pharmacy | null);
const bbs = computed(() => s.work.bloodBanks as BloodBank[]);
const lab = computed(() => s.work.lab as Lab | null);
const ready = computed(() => s.ready && !s.guest && s.workLoaded);
const ambStats = useCall<{ items: ListingStats[] }>('ambulance_stats', {}, () => ({ enabled: ready.value && amb.value.length > 0 }));
const phStats = useCall<CallSummary>('pharmacy_stats', {}, () => ({ enabled: ready.value && !!ph.value }));
const bbStats = useCall<{ items: ListingStats[] }>('bloodbank_stats', {}, () => ({ enabled: ready.value && bbs.value.length > 0 }));
const finds = useCall<{ items: { answer: string | null }[]; findOff: boolean }>('med_find_requests', {}, () => ({ enabled: ready.value && ph.value?.status === 'approved' }));
const after = useAfterChange();
const title = computed(() => tr('My work', 'আমার কাজ'));

const hasBusiness = computed(() => amb.value.length > 0 || !!ph.value || bbs.value.length > 0 || !!lab.value);
// Windows অ্যাপ (ফিচার সুইচ desktop): ফার্মেসি, প্যাথলজি, ডাক্তার/সহকারীকে — ডেস্কটপ অ্যাপের ভেতরে নয়
const desktopLink = computed(() => invFeature('desktop') && !isDesktop() && (!!ph.value || !!lab.value || s.work.professional === 'doctor' || s.work.assisting.length > 0));
const empty = computed(() => !hasBusiness.value && !s.work.professional && !s.work.assisting.length);

// সব মিলিয়ে হিসাব
const sumCalls = (k: 7 | 30) =>
  (ambStats.data?.items ?? []).reduce((a, x) => a + x.calls[k], 0) + (phStats.data?.calls[k] ?? 0) + (bbStats.data?.items ?? []).reduce((a, x) => a + x.calls[k], 0);
const statuses = computed(() => [...amb.value.map((a) => a.status), ph.value?.status, ...bbs.value.map((b) => b.status), lab.value?.status].filter(Boolean) as string[]);
const live = computed(() => statuses.value.filter((x) => x === 'approved').length);
const review = computed(() => statuses.value.filter((x) => x === 'pending').length);
const openFinds = computed(() => (finds.data && !finds.data.findOff ? finds.data.items.filter((r) => !r.answer).length : null));
const statsLoaded = computed(() => !!(ambStats.data || phStats.data || bbStats.data));

const sumOf = (ids: string[], items: ListingStats[] | undefined): CallSummary | null => {
  const m = new Map((items ?? []).map((x) => [x.id, x]));
  const xs = ids.map((id) => m.get(id)).filter(Boolean) as ListingStats[];
  if (!xs.length) return null;
  const add = (k: 7 | 30 | 90 | 'total') => xs.reduce((a, x) => a + x.calls[k], 0);
  return { calls: { 7: add(7), 30: add(30), 90: add(90), total: add('total') }, callers: 0, daily: [] };
};
const needs = (x: { docsMissing?: boolean; docsRequested?: boolean }) => !!(x.docsMissing || x.docsRequested);
const anyNotice = computed(() => amb.value.some(needs) || (!!ph.value && needs(ph.value)) || bbs.value.some(needs) || (!!lab.value && needs(lab.value)));
const canAdd = computed(() => amb.value.length < MAX_AMBULANCES || !ph.value || bbs.value.length < MAX_BLOODBANKS || !lab.value);
const ambSub = computed(() => {
  const a = amb.value;
  return [
    tr(`${a.length} in total`, `${num(a.length)}টা`),
    ...(a.some((x) => x.status === 'approved') ? [tr(`${a.filter((x) => x.status === 'approved' && x.available).length} free`, `${num(a.filter((x) => x.status === 'approved' && x.available).length)}টা খালি`)] : []),
    ...statusCounts(a.map((x) => x.status)),
  ].join(' · ');
});
const bbSub = computed(() => [bbs.value.length === 1 ? bbs.value[0].name : tr(`${bbs.value.length} blood banks`, `${num(bbs.value.length)}টা`), ...statusCounts(bbs.value.map((b) => b.status))].join(' · '));
</script>

<template>
  <Workspace v-if="!ready" :title="title"><WorkGate /></Workspace>
  <Workspace v-else-if="empty" :title="title">
    <WorkEmpty
      icon="storefront"
      :title="tr('Do you have a business?', 'আপনার কি কোনো ব্যবসা আছে?')"
      :text="tr('Ambulance, pharmacy, blood bank or lab — add it so patients nearby can find you.', 'অ্যাম্বুলেন্স, ফার্মেসি, ব্লাড ব্যাংক বা প্যাথলজি — যোগ করলে আশেপাশের রোগীরা আপনাকে খুঁজে পাবেন।')"
    >
      <Button icon="add" @click="nav('/work/add')">{{ tr('Add business', 'ব্যবসা যোগ করুন') }}</Button>
    </WorkEmpty>
  </Workspace>
  <Workspace v-else :title="title">
    <template #intro><h2 class="t-headline work-mobile-title">{{ title }}</h2></template>
    <template v-if="canAdd" #actions>
      <Button icon="add" @click="nav('/work/add')">{{ hasBusiness ? tr('Add another business', 'আরেকটা ব্যবসা যোগ করুন') : tr('Add business', 'ব্যবসা যোগ করুন') }}</Button>
    </template>
    <div v-if="hasBusiness" class="stats work-stats">
      <Stat icon="call" :value="statsLoaded ? num(sumCalls(7)) : '—'" :label="tr('Calls from the app · 7 days', 'অ্যাপ থেকে ফোন · ৭ দিনে')" />
      <Stat icon="call" :value="statsLoaded ? num(sumCalls(30)) : '—'" :label="tr('Calls from the app · 30 days', 'অ্যাপ থেকে ফোন · ৩০ দিনে')" />
      <Stat icon="check_circle" :value="num(live)" :label="tr('Listed', 'তালিকায় আছে')" />
      <Stat icon="schedule" :value="num(review)" :label="tr('Under review', 'যাচাই চলছে')" />
      <Stat v-if="openFinds != null" icon="medication" :value="num(openFinds)" :label="tr('Medicine requests', 'ওষুধ খোঁজার অনুরোধ')" />
    </div>

    <div v-if="anyNotice" class="col gap-8">
      <DocsNeededNotice v-for="a in amb.filter(needs)" :key="'a' + a.id" kind="ambulance" :id="a.id" :specs="ambulanceDocs()" :item="a" :title="`${businessName('ambulance')} · ${a.name} · ${a.vehicleNo}`" @done="after" />
      <DocsNeededNotice v-if="ph && needs(ph)" key="ph" kind="pharmacy" :id="ph.id" :specs="pharmacyDocs()" :item="ph" :title="`${businessName('pharmacy')} · ${ph.name}`" @done="after" />
      <DocsNeededNotice v-for="b in bbs.filter(needs)" :key="'b' + b.id" kind="bloodbank" :id="b.id" :specs="bloodBankDocs(b.kind)" :item="b" :title="`${businessName('bloodbank')} · ${b.name}`" @done="after" />
      <DocsNeededNotice v-if="lab && needs(lab)" key="lab" kind="lab" :id="lab.id" :specs="labDocs()" :item="lab" :title="`${businessName('lab')} · ${lab.name}`" @done="after" />
    </div>

    <div class="work-grid">
      <BizCard
        v-if="s.work.professional"
        :icon="badgeIcon(s.work.professional)"
        :title="s.work.professional === 'nurse' ? tr('My home care', 'আমার বাসায় সেবা') : tr('My chamber', 'আমার চেম্বার')"
        :sub="s.work.professional === 'nurse' ? badgeLabel(s.work.professional) : badgeLabel(s.work.professional) + tr(' · bookings, patients, setup', ' · বুকিং, রোগী, সেটআপ')"
        to="/work/pro"
      >
        <template #actions><RouterLink to="/work/pro" class="btn small has-icon"><Icon name="calendar_month" />{{ tr('Open', 'খুলুন') }}</RouterLink></template>
      </BizCard>

      <BizCard
        v-if="writesRx(s.work.professional) || s.work.assisting.length"
        icon="edit_note"
        :title="tr('Prescriptions', 'প্রেসক্রিপশন')"
        :sub="writesRx(s.work.professional)
          ? tr('Patients, write prescriptions, team, report reviews', 'রোগী, প্রেসক্রিপশন লেখা, টিম, রিপোর্ট দেখানো')
          : tr('Doctor\'s assistant · ', 'ডাক্তারের সহকারী · ') + s.work.assisting.map((d) => d.name).join(', ')"
        to="/work/rx"
      >
        <template #actions><RouterLink to="/work/rx" class="btn small has-icon"><Icon name="edit_note" />{{ tr('Open', 'খুলুন') }}</RouterLink></template>
      </BizCard>

      <BizCard v-if="amb.length" kind="ambulance" :sub="ambSub" :calls="sumOf(amb.map((a) => a.id), ambStats.data?.items)" to="/work/ambulance">
        <ul class="work-mini-list">
          <li v-for="a in amb.slice(0, 4)" :key="a.id">
            <span class="ellipsis grow">{{ a.vehicleNo || a.name }}</span>
            <span v-if="a.status === 'approved'" :class="a.available ? 'work-dot on' : 'work-dot'">{{ a.available ? tr('Available', 'খালি আছে') : tr('Busy', 'ব্যস্ত') }}</span>
            <StatusTag v-else :status="a.status" />
          </li>
          <li v-if="amb.length > 4" class="muted">+{{ num(amb.length - 4) }}</li>
        </ul>
        <template #actions>
          <RouterLink v-if="amb.length < MAX_AMBULANCES" to="/work/ambulance/new" class="btn outline small has-icon"><Icon name="add" />{{ tr('Add ambulance', 'অ্যাম্বুলেন্স যোগ করুন') }}</RouterLink>
        </template>
      </BizCard>

      <BizCard
        v-if="ph"
        kind="pharmacy"
        :sub="ph.name"
        :calls="phStats.data ?? null"
        to="/work/pharmacy"
        :note="ph.closedUntil > 0 ? tr('Closed until ', 'বন্ধ থাকবে ') + formatDate(ph.closedUntil) + tr('', ' পর্যন্ত') : null"
      >
        <template #status><StatusTag :status="ph.status" :closed="ph.closedUntil > 0" /></template>
        <template #actions>
          <RouterLink v-if="ph.status === 'approved'" to="/work/pharmacy/orders" class="btn outline small has-icon"><Icon name="medication" />{{ tr('Prescriptions & orders', 'প্রেসক্রিপশন ও অর্ডার') }}</RouterLink>
          <RouterLink to="/work/pharmacy/edit" class="btn text small has-icon"><Icon name="edit" />{{ tr('Edit details', 'তথ্য বদলান') }}</RouterLink>
        </template>
      </BizCard>

      <BizCard v-if="bbs.length" kind="bloodbank" :sub="bbSub" :calls="sumOf(bbs.map((b) => b.id), bbStats.data?.items)" to="/work/bloodbank">
        <template #status><StatusTag v-if="bbs.length === 1" :status="bbs[0].status" /></template>
        <template #actions>
          <RouterLink v-if="bbs.length < MAX_BLOODBANKS" to="/work/bloodbank/new" class="btn outline small has-icon"><Icon name="add" />{{ tr('Add blood bank', 'ব্লাড ব্যাংক যোগ করুন') }}</RouterLink>
        </template>
      </BizCard>

      <BizCard v-if="lab" kind="lab" :sub="lab.name" to="/work/lab">
        <template #status><StatusTag :status="lab.status" /></template>
        <template #actions>
          <RouterLink v-if="lab.status === 'approved'" to="/work/lab/orders" class="btn outline small has-icon"><Icon name="list_alt" />{{ tr('Tests & reports', 'পরীক্ষা ও রিপোর্ট') }}</RouterLink>
          <RouterLink to="/work/lab/edit" class="btn text small has-icon"><Icon name="edit" />{{ tr('Edit details', 'তথ্য বদলান') }}</RouterLink>
        </template>
      </BizCard>

      <BizCard v-if="desktopLink" icon="storefront" :title="DT.dlTitle()" :sub="DT.dlIntro()" to="/desktop-app" data-testid="desktop-app-link">
        <template #actions><RouterLink to="/desktop-app" class="btn outline small has-icon"><Icon name="add" />{{ DT.dlButton() }}</RouterLink></template>
      </BizCard>
    </div>
  </Workspace>
</template>
