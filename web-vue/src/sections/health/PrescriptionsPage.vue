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
import { useNav, useSearchParams } from '@/lib/nav';
import { useFlash } from './common';
import type { Rx } from './rx';
import ServiceTabs from '../services/ServiceTabs.vue';
import { RX_HOME, rxPath } from '../services/data';
import RxListCard from './RxListCard.vue';
import RxDetail from './RxDetail.vue';
import RxClaimDialog from './RxClaimDialog.vue';

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
const shown = computed(() => (q.data ?? []).filter((r) => !who.value || (r.patient?.name ?? '').trim() === who.value));
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
        <div v-if="names.length > 1" class="chips scroll" role="group" :aria-label="tr('Patient', 'রোগী')">
          <button v-for="n in ['', ...names]" :key="n || 'all'" type="button" class="chip" :aria-pressed="(who ?? '') === n" @click="setWho(n)">
            <Icon v-if="(who ?? '') === n" name="check_circle" />{{ n || tr('Everyone', 'সবাই') }}
          </button>
        </div>
        <SkeletonCards v-if="q.isPending" :n="3" :h="140" />
        <ErrorBox v-else-if="q.isError" :error="errorText(q.error)" :on-retry="() => q.refetch()" />
        <EmptyState v-else-if="!shown.length" icon="medication" :title="tr('No prescriptions yet', 'এখনো কোনো প্রেসক্রিপশন নেই')" :text="tr('Prescriptions from doctors on Lifolo appear here. Got a paper one? Add it with its code.', 'Lifolo-তে ডাক্তারের দেওয়া প্রেসক্রিপশন এখানে আসে। কাগজেরটা কোড দিয়ে যোগ করুন।')">
          <Button variant="outline" icon="add" @click="claiming = true">{{ tr('Add with code', 'কোড দিয়ে যোগ করুন') }}</Button>
        </EmptyState>
        <template v-else>
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
