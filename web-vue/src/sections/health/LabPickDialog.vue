<script setup lang="ts">
// প্যাথলজি বাছাই: প্রতিটায় কয়টা পরীক্ষা হয় ("৩টার ৩টা"), আনুমানিক কত, কোনটা নেই, রিপোর্ট কতক্ষণে —
// বেশি পরীক্ষা → কম দাম → কাছের আগে (সার্ভার সাজায়)। দামের তালিকা না থাকলে "দাম জানা নেই — ফোনে জেনে নিন"।
// বাসা থেকে নমুনা নিলে পাঠানোর আগে বেছে নেওয়া যায় (ঠিকানা, ফোন, দিন, সময়)।
import { computed, ref } from 'vue';
import { keepPreviousData } from '@tanstack/vue-query';
import { ChoiceChips, Dialog, DirectoryCard, EmptyState, ErrorText, FilterBar, Loading } from '@/ui/kit';
import AreaPicker from '@/ui/AreaPicker.vue';
import type { AreaValue } from '@/ui/areaFilter';
import { call, errorText } from '@/lib/api';
import { useQ } from '@/lib/query';
import { tr } from '@/lib/i18n';
import { areaLabel } from '@/lib/geo';
import { useCareMode } from '@/lib/care';
import { NO_PRICE, breakdownText, coverEstimateText, discountText, hereText, taka } from '@/lib/labprice';
import type { LastHome, PlaceLab, Rx } from './rx';
import { callTel, startArea, useDebounced, type HomeReq, type LabOption } from './rxactivity';
import SendError from './SendError.vue';
import LabSendDialog from './LabSendDialog.vue';

const props = defineProps<{ rx: Rx; only?: PlaceLab }>();
const emit = defineEmits<{ close: []; sent: [name: string, home: boolean] }>();
const start = startArea();
const care = useCareMode();
// only: প্যাথলজির পাতা থেকে এলে শুধু এই প্যাথলজি — তার জেলায় খোঁজা, এলাকা বদলানো নেই
const area = ref<AreaValue | null>(props.only?.district ? { district: props.only.district, upazilaId: null } : start);
const showArea = ref(false);
const chosen = ref<string[]>(props.rx.tests ?? []);
const busy = ref<string | null>(null);
const err = ref<string | null>(null);
const homeFor = ref<LabOption | null>(null);
// চিপ টিপতে টিপতে প্রতিবার নয় — থামার ৪৫০ মিলিসেকেন্ড পর (সার্ভারে দিনের সীমাও আছে)
const asked = useDebounced(() => chosen.value, 450);
const q = useQ<{ items?: LabOption[]; lastHome?: LastHome | null }>(() => ({
  queryKey: ['rx_lab_options', props.rx.id, area.value?.district, area.value?.upazilaId ?? null, asked.value.join('|'), care.value?.uid ?? null, props.only?.id ?? null],
  queryFn: () =>
    call<{ items?: LabOption[]; lastHome?: LastHome | null }>('rx_lab_options', {
      rxId: props.rx.id, district: area.value!.district, tests: asked.value, ...(area.value?.upazilaId ? { upazilaId: area.value.upazilaId } : {}),
      // প্যাথলজির পাতা থেকে: শুধু সেটা (জেলার তালিকার সীমার বাইরে হলেও)
      ...(props.only ? { placeId: props.only.id } : {}),
    }),
  enabled: !!area.value && asked.value.length > 0,
  placeholderData: keepPreviousData,
}));
const testOptions = computed(() => (props.rx.tests ?? []).map((t) => ({ value: t, label: t })));
const items = computed<LabOption[]>(() => {
  const all = q.data?.items ?? [];
  const only = props.only;
  // শুধু এই প্যাথলজি; তালিকায় না এলে (বা আনা না গেলে) দামের হিসাব ছাড়াই — পাঠানো তবু যায়, সার্ভার মিলিয়ে নেয়
  const mine = only ? all.filter((l) => l.id === only.id) : all;
  return !only || mine.length || !(q.data || q.isError) ? mine : [{
    id: only.id, name: only.name, phone: only.phone, address: only.address, district: only.district, upazilaId: only.upazilaId, facilityId: only.facilityId,
    homeCollection: only.homeCollection, hours: only.hours, suggested: false, priced: false, have: null, total: chosen.value.length, missing: [], items: [],
    subtotal: null, discountPct: null, estimate: null, homeFee: only.homeCollection ? only.prices?.homeFee ?? null : null, tat: '',
  }];
});

async function send(lab: LabOption) {
  if (busy.value || !chosen.value.length) {
    if (!chosen.value.length) err.value = tr('Choose the tests', 'পরীক্ষা বেছে নিন');
    return;
  }
  if (lab.homeCollection) {
    homeFor.value = lab;
    return;
  }
  busy.value = lab.id;
  err.value = null;
  try {
    await call('rx_send_lab', { rxId: props.rx.id, labId: lab.id, tests: chosen.value });
    emit('sent', lab.name, false);
  } catch (e) {
    err.value = errorText(e);
  }
  busy.value = null;
}
async function submitHome(home: HomeReq | null) {
  const h = homeFor.value!;
  await call('rx_send_lab', { rxId: props.rx.id, labId: h.id, tests: chosen.value, ...(home ? { home } : {}) });
  const nm = h.name;
  homeFor.value = null;
  emit('sent', nm, !!home);
}
const homeText = (lab: LabOption) =>
  lab.homeCollection ? [tr('Sample from home', 'বাসা থেকে নমুনা নেয়'), lab.homeFee != null ? (lab.homeFee ? taka(lab.homeFee) : tr('free', 'ফ্রি')) : ''].filter(Boolean).join(' · ') : '';
</script>

<template>
  <Dialog size="wide" :title="only?.name ?? tr('Choose a lab', 'প্যাথলজি বাছুন')" @close="!busy && emit('close')">
    <span class="t-label-l">{{ tr('Tests to send', 'কোন পরীক্ষা পাঠাবেন') }}</span>
    <ChoiceChips v-model="chosen" multi :options="testOptions" />
    <FilterBar v-if="!only" :area-label="area ? areaLabel(area.district, area.upazilaId) : ''" :on-area="() => (showArea = true)" />
    <SendError :text="err" />
    <div class="col gap-12">
      <EmptyState v-if="!area" icon="location_on" :title="tr('Choose area', 'এলাকা বেছে নিন')" />
      <EmptyState v-else-if="!chosen.length" icon="list_alt" :title="tr('Choose the tests', 'পরীক্ষা বেছে নিন')" />
      <Loading v-else-if="q.isPending" />
      <ErrorText v-else-if="q.isError && !only" :text="errorText(q.error)" />
      <EmptyState v-else-if="!items.length" icon="local_hospital" :title="tr('No lab in the app here yet', 'এখানে এখনো অ্যাপে কোনো প্যাথলজি নেই')" :text="tr('You can still take the prescription to any lab.', 'যেকোনো প্যাথলজিতে প্রেসক্রিপশন নিয়ে গিয়েও করাতে পারেন।')" />
      <template v-else>
        <!-- প্যাথলজির পাতা থেকে এলে: "এখানে হয় ৩/৪টা পরীক্ষা; হয় না: X" আর দামের ভাঙা হিসাব (দাম, ছাড়, মোট) -->
        <DirectoryCard
          v-for="lab in items"
          :key="lab.id"
          :title="lab.name"
          icon="local_hospital"
          :status="lab.suggested ? { text: tr('Doctor suggested', 'ডাক্তারের সুপারিশ'), good: true } : null"
          :subtitle="!lab.priced ? NO_PRICE() : only ? hereText(lab.have ?? 0, lab.total, lab.missing) : coverEstimateText(lab.have ?? 0, lab.total, lab.estimate)"
          :meta="lab.priced && lab.discountPct && !only ? discountText(lab.discountPct) : null"
          :details="[
            ['payments', lab.priced && only ? breakdownText(lab.subtotal, lab.discountPct, lab.estimate) : ''],
            ['warning', lab.priced && !only && lab.missing.length ? tr('Not here: ', 'এখানে হয় না: ') + lab.missing.join(', ') : ''],
            ['schedule', lab.tat ? tr('Report: ', 'রিপোর্ট: ') + lab.tat : ''],
            ['home_health', homeText(lab)],
            ['location_on', lab.address],
          ]"
          :primary="{ label: tr('Send tests', 'পরীক্ষা পাঠান'), icon: 'send', busy: busy === lab.id, onClick: () => send(lab) }"
          :secondary="lab.phone ? { label: tr('Call', 'ফোন'), icon: 'call', onClick: () => callTel(lab.phone) } : null"
        />
      </template>
    </div>
    <AreaPicker v-if="showArea" :value="area" @close="showArea = false" @picked="(v) => { area = v; showArea = false; }" />
    <LabSendDialog v-if="homeFor" :title="homeFor.name" :home-fee="homeFor.homeFee" :last="q.data?.lastHome ?? null" :on-submit="submitHome" @close="homeFor = null">
      <template #header>
        <span v-if="homeFor.priced" class="t-body-m semi">{{ coverEstimateText(homeFor.have ?? 0, homeFor.total, homeFor.estimate) }}</span>
        <span v-else class="t-body-s muted">{{ NO_PRICE() }}</span>
      </template>
    </LabSendDialog>
  </Dialog>
</template>
