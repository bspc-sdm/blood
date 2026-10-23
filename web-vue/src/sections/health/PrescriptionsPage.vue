<script setup lang="ts">
// রোগী: আমার প্রেসক্রিপশন — অ্যাপের MyPrescriptionsScreen, RxListCard, RxDetailDialog, RxPaper, RxClaimDialog;
// প্রিন্ট অ্যাপের RxPdf.kt-এর কাগজের মতো (শুধু কাগজটাই ছাপা হয়)।
// থাকে সেবা → ফার্মেসি → "আমার প্রেসক্রিপশন" (/services/pharmacy/prescriptions) আর প্রোফাইলে — স্বাস্থ্যে নয় (মালিক)।
import { computed, ref } from 'vue';
import { Button, EmptyState, ErrorBox, Icon, InfoText, SkeletonCards } from '@/ui/kit';
import ListDetail from '@/shell/ListDetail.vue';
import { usePageTitle } from '@/shell/chrome';
import { call, errorText } from '@/lib/api';
import { useQ } from '@/lib/query';
import { num, tr, useLang } from '@/lib/i18n';
import { useSession } from '@/lib/session';
import { useCareMode } from '@/lib/care';
import { useHealthConsentPrompt } from '@/lib/consent';
import { useNav, useSearchParams } from '@/lib/nav';
import { useFlash } from './common';
import type { Rx } from './rx';
import ServiceTabs from '../services/ServiceTabs.vue';
import { RX_HOME, rxPath } from '../services/data';
import RxListCard from './RxListCard.vue';
import RxDetail from './RxDetail.vue';
import RxClaimDialog from './RxClaimDialog.vue';
import { RX_TABS, inTab, tabLabel, type RxTab } from './rxstate';

// স্বাস্থ্য তথ্যের সম্মতি: সুইচ চালু আর সম্মতি না থাকলে প্রথমবার খোলায় একবার জিজ্ঞেস (backend/docs/CONSENT.md)
useHealthConsentPrompt();

const props = defineProps<{ id: string | null }>();
usePageTitle(() => tr('My prescriptions', 'আমার প্রেসক্রিপশন'));
const nav = useNav();
const s = useSession();
const lang = useLang();
const care = useCareMode();
const [params, setParams] = useSearchParams();
const who = computed(() => params.value.get('who'));
const q = useQ<Rx[]>(() => ({
  queryKey: ['rx_mine', care.value?.uid ?? null, lang.value, s.uid],
  queryFn: () => call<{ items?: Rx[] }>('rx_mine').then((r) => r.items ?? []),
  enabled: !s.guest,
}));
const claiming = ref(false);
const [info, setInfo] = useFlash();
const names = computed(() => [...new Set((q.data ?? []).map((r) => (r.patient?.name ?? '').trim()).filter(Boolean))]);
const forWho = computed(() => (q.data ?? []).filter((r) => !who.value || (r.patient?.name ?? '').trim() === who.value));
// ট্যাব: চলমান (আগে থেকে খোলা) / পূর্বের (শেষ আর বাতিল) / সব — সার্ভারের state.phase দিয়ে (backend/docs/RX-STATE.md); প্রতিটায় নতুনটা আগে
const tab = computed<RxTab>(() => {
  const t = params.value.get('tab');
  return t === 'past' || t === 'all' ? t : 'active';
});
const counts = computed(() => Object.fromEntries(RX_TABS.map((t) => [t, forWho.value.filter((r) => inTab(r, t)).length])) as Record<RxTab, number>);
const shown = computed(() => forWho.value.filter((r) => inTab(r, tab.value)).sort((a, b) => b.createdAt - a.createdAt));
function setTab(t: RxTab) {
  const p = new URLSearchParams(params.value);
  if (t === 'active') p.delete('tab');
  else p.set('tab', t);
  setParams(p, { replace: true });
}
const qs = () => (params.value.toString() ? `?${params.value}` : '');
const open = (rid: string) => nav(`${rxPath(rid)}${qs()}`);
function setWho(n: string) {
  const p = new URLSearchParams(params.value);
  if (n) p.set('who', n);
  else p.delete('who');
  setParams(p, { replace: true });
}
function onAdded(n: number) {
  claiming.value = false;
  setInfo(tr(`Added ${n} prescription(s)`, `${num(n)}টা প্রেসক্রিপশন যোগ হয়েছে`));
  q.refetch();
}
const fromList = computed(() => (props.id ? q.data?.find((r) => r.id === props.id) ?? null : null));
</script>

<template>
  <ListDetail :has-detail="!!(id && !s.guest)">
    <div class="list-inner">
      <ServiceTabs kind="pharmacy" sub="rx" />
      <EmptyState v-if="s.guest" icon="medication" :title="tr('My prescriptions', 'আমার প্রেসক্রিপশন')" :text="tr('Prescriptions from doctors on Lifolo appear here. Got a paper one? Add it with its code.', 'Lifolo-তে ডাক্তারের দেওয়া প্রেসক্রিপশন এখানে আসে। কাগজেরটা কোড দিয়ে যোগ করুন।')">
        <Button @click="s.login()">{{ tr('Log in', 'লগইন') }}</Button>
      </EmptyState>
      <template v-else>
        <div class="row">
          <h2 class="t-headline grow">{{ tr('My prescriptions', 'আমার প্রেসক্রিপশন') }}</h2>
          <Button v-if="q.data?.length" variant="tonal" small icon="add" @click="claiming = true">{{ tr('Add with code', 'কোড দিয়ে যোগ করুন') }}</Button>
        </div>
        <InfoText :text="info" />
        <SkeletonCards v-if="q.isPending" :n="3" :h="140" />
        <ErrorBox v-else-if="q.isError" :error="errorText(q.error)" :on-retry="() => q.refetch()" />
        <EmptyState v-else-if="!(q.data?.length ?? 0)" icon="medication" :title="tr('No prescriptions yet', 'এখনো কোনো প্রেসক্রিপশন নেই')" :text="tr('Prescriptions from doctors on Lifolo appear here. Got a paper one? Add it with its code.', 'Lifolo-তে ডাক্তারের দেওয়া প্রেসক্রিপশন এখানে আসে। কাগজেরটা কোড দিয়ে যোগ করুন।')">
          <Button variant="outline" icon="add" @click="claiming = true">{{ tr('Add with code', 'কোড দিয়ে যোগ করুন') }}</Button>
        </EmptyState>
        <template v-else>
          <div class="rx-tabs" role="tablist" :aria-label="tr('Prescriptions', 'প্রেসক্রিপশন')">
            <button v-for="t in RX_TABS" :key="t" type="button" role="tab" :aria-selected="tab === t" :data-testid="`rx-tab-${t}`" @click="setTab(t)">{{ tabLabel(t, counts[t]) }}</button>
          </div>
          <div v-if="names.length > 1" class="chips scroll" role="group" :aria-label="tr('Patient', 'রোগী')">
            <button v-for="n in ['', ...names]" :key="n || 'all'" type="button" class="chip" :aria-pressed="(who ?? '') === n" @click="setWho(n)">
              <Icon v-if="(who ?? '') === n" name="check_circle" />{{ n || tr('Everyone', 'সবাই') }}
            </button>
          </div>
          <div v-if="!shown.length" class="rx-tab-empty" data-testid="rx-tab-empty">
            <p class="t-body-m muted">{{ tab === 'active' ? tr('No ongoing prescriptions right now.', 'এখন চলমান কোনো প্রেসক্রিপশন নেই।') : tab === 'past' ? tr('No previous prescriptions.', 'পূর্বের কোনো প্রেসক্রিপশন নেই।') : tr('No prescriptions.', 'কোনো প্রেসক্রিপশন নেই।') }}</p> <!-- bn-ok -->
            <Button v-if="tab === 'active' && counts.past > 0" variant="text" icon="history" @click="setTab('past')">{{ tr('See previous', 'পূর্বেরগুলো দেখুন') }} ({{ num(counts.past) }})</Button> <!-- bn-ok -->
          </div>
          <RxListCard v-for="rx in shown" :key="rx.id" :rx="rx" :selected="rx.id === id" @click="open(rx.id)" />
        </template>
      </template>
      <RxClaimDialog v-if="claiming" @close="claiming = false" @added="onAdded" />
    </div>
    <template #detail>
      <RxDetail v-if="id && !s.guest" :id="id" :key="id" :initial="fromList" :list-loading="q.isPending" :back="() => nav(`${RX_HOME}${qs()}`)" />
    </template>
    <template v-if="!s.guest" #placeholder><EmptyState icon="medication" :title="tr('Pick a prescription', 'একটা প্রেসক্রিপশন বেছে নিন')" /></template>
  </ListDetail>
</template>

<style scoped>
/* চলমান / পূর্বের / সব — নিচে দাগওয়ালা ট্যাব (উপরের সেবা/ফার্মেসির পিল থেকে আলাদা দেখায়) */
.rx-tabs { display: flex; border-bottom: 1px solid var(--outline-variant); }
.rx-tabs button {
  flex: 1 1 0; min-width: 0; min-height: 44px; padding: 0 8px; border: 0; background: transparent;
  color: var(--on-surface-variant); font: inherit; font-size: 14px; font-weight: 500; white-space: nowrap;
  border-bottom: 3px solid transparent; margin-bottom: -1px; cursor: pointer;
}
.rx-tabs button[aria-selected='true'] { color: var(--primary); border-bottom-color: var(--primary); font-weight: 700; }
.rx-tabs button:focus-visible { outline: 2px solid var(--primary); outline-offset: -2px; }
.rx-tab-empty { display: flex; flex-direction: column; align-items: flex-start; gap: 4px; padding: 16px 4px; }
</style>
