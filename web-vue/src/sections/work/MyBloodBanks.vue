<script setup lang="ts">
// /work/bloodbank — নিজের ব্লাড ব্যাংক (সর্বোচ্চ ৩টা): তালিকা, হিসাব, যোগ/বদল/সরানো (অ্যাপের MyBloodBanksSection, BloodBankEditDialog)
import { computed, ref } from 'vue';
import { Button, ConfirmDialog, Icon, RowMenu, Tag } from '@/ui/kit';
import { useMedia } from '@/shell/chrome';
import { useSession } from '@/lib/session';
import { useCall } from '@/lib/query';
import { call, errorText } from '@/lib/api';
import { num, tr } from '@/lib/i18n';
import { areaLabel } from '@/lib/geo';
import { useNav } from '@/lib/nav';
import { MAX_BLOODBANKS, bbKindLabel, bloodBankDocs, callsLine, closedDaysText, hoursText, myBusinessTitle, useAfterChange, useAreas, useCatalog, useFlash, type BloodBank, type ListingStats } from './shared';
import BizPage from './BizPage.vue';
import WorkGate from './WorkGate.vue';
import WorkEmpty from './WorkEmpty.vue';
import Flash from './Flash.vue';
import Stat from './Stat.vue';
import StatsDialog from './StatsDialog.vue';
import StatusTag from './StatusTag.vue';
import StatusReason from './StatusReason.vue';
import AppealButton from './AppealButton.vue';
import DocsNeededNotice from './DocsNeededNotice.vue';
import ProviderPreview from '@/ui/listing/ProviderPreview.vue';
import StockEditor, { type StockResult } from '@/ui/listing/StockEditor.vue';
import { useOwnListings } from './ownListing';

const s = useSession();
const nav = useNav();
const wide = useMedia('(min-width: 1100px)');
const ready = computed(() => s.ready && !s.guest && s.workLoaded);
const list = computed(() => s.work.bloodBanks as BloodBank[]);
const stats = useCall<{ items: ListingStats[] }>('bloodbank_stats', {}, () => ({ enabled: ready.value && list.value.length > 0 }));
const cat = useCatalog('bloodBankService');
const after = useAfterChange();
const [flash, setFlash] = useFlash();
const del = ref<BloodBank | null>(null);
const delBusy = ref(false);
const delErr = ref<string | null>(null);
const details = ref<BloodBank | null>(null);
useAreas();
const title = computed(() => myBusinessTitle('bloodbank'));
const st = computed(() => new Map((stats.data?.items ?? []).map((x) => [x.id, x])));
const canAdd = computed(() => list.value.length < MAX_BLOODBANKS);
// "রোগীরা আপনাকে যেভাবে দেখেন" + রক্তের মজুদ (প্রতিটা ব্লাড ব্যাংকের; স্থগিত হলে নয়)
const own = useOwnListings('bloodbank');
const live = computed(() => list.value.filter((b) => b.status !== 'suspended'));
const stockSaved = (b: BloodBank, r: StockResult) => own.patch(b, { stock: r.stock, stockUpdatedAt: r.stockUpdatedAt, stockStale: r.stockStale });
async function remove() {
  if (!del.value) return;
  delBusy.value = true;
  delErr.value = null;
  try {
    await call('bloodbank_delete', { id: del.value.id });
    await after();
    del.value = null;
    setFlash(tr('Removed', 'মুছে ফেলা হয়েছে'));
  } catch (e) {
    delErr.value = errorText(e);
  } finally {
    delBusy.value = false;
  }
}
const place = (b: BloodBank) => [b.address, areaLabel(b.district, b.upazilaId)].filter(Boolean).join(', ');
const hours = (b: BloodBank) => [hoursText(b), closedDaysText(b.closedDays)].filter(Boolean).join(' · ');
const askDel = (b: BloodBank) => {
  delErr.value = null;
  del.value = b;
};
</script>

<template>
  <BizPage v-if="!ready" :title="title"><WorkGate /></BizPage>
  <BizPage v-else :title="title">
    <template v-if="canAdd" #actions><Button icon="add" @click="nav('/work/bloodbank/new')">{{ tr('Add blood bank', 'ব্লাড ব্যাংক যোগ করুন') }}</Button></template>
    <Flash :msg="flash" />
    <!-- বড় পর্দায় দুই ভাগ (নিচে ফাঁকা নয়): বাঁয়ে প্রতিটার "রোগীরা যেভাবে দেখেন" + রক্তের মজুদ, ডানে হিসাব আর ব্লাড ব্যাংকের তথ্য -->
    <div class="ws-grid side">
      <div class="col gap-16" :style="{ minWidth: 0 }">
        <ProviderPreview
          v-for="b in live"
          :key="'pv-' + b.id"
          :l="own.listing(b)"
          :public-to="b.status === 'approved' ? `/services/bloodbank/${b.id}` : null"
          :store-key="'bloodbank.' + b.id"
        >
          <StockEditor :id="b.id" :stock="own.raw(b).stock" @saved="(r) => stockSaved(b, r)" />
        </ProviderPreview>
        <DocsNeededNotice v-for="b in list" :key="b.id" kind="bloodbank" :id="b.id" :specs="bloodBankDocs(b.kind)" :item="b" :title="b.name" @done="after" />
        <WorkEmpty v-if="!list.length" icon="bloodtype" :title="tr('No blood bank added yet', 'এখনো কোনো ব্লাড ব্যাংক যোগ করা হয়নি')">
          <Button icon="add" @click="nav('/work/bloodbank/new')">{{ tr('Add blood bank', 'ব্লাড ব্যাংক যোগ করুন') }}</Button>
        </WorkEmpty>
      </div>
      <div v-if="list.length" class="col gap-16" :style="{ minWidth: 0 }">
        <div class="stats work-stats">
          <Stat icon="bloodtype" :value="num(list.length)" :label="tr('Blood banks', 'ব্লাড ব্যাংক')" />
          <Stat icon="check_circle" :value="num(list.filter((b) => b.status === 'approved').length)" :label="tr('Listed', 'তালিকায় আছে')" />
          <Stat icon="call" :value="stats.data ? num(stats.data.items.reduce((n, x) => n + x.calls[7], 0)) : '—'" :label="tr('Calls from the app · 7 days', 'অ্যাপ থেকে ফোন · ৭ দিনে')" />
          <Stat icon="call" :value="stats.data ? num(stats.data.items.reduce((n, x) => n + x.calls[30], 0)) : '—'" :label="tr('Calls from the app · 30 days', 'অ্যাপ থেকে ফোন · ৩০ দিনে')" />
        </div>
        <div :class="wide ? 'col gap-12' : 'work-cards'">
          <article v-for="b in list" :key="b.id" class="card col gap-8">
            <div class="row top">
              <div class="col gap-2 grow">
                <span class="t-title-m">{{ b.name }}</span>
                <span class="t-body-s muted">{{ bbKindLabel(b.kind) }}</span>
              </div>
              <StatusTag :status="b.status" />
            </div>
            <StatusReason :status="b.status" :reason="b.statusReason" />
            <div class="row wrap gap-6"><Tag v-for="x in b.services" :key="x" tone="primary">{{ cat.label(x) }}</Tag></div>
            <span v-if="place(b)" class="icon-line"><Icon name="location_on" /><span>{{ place(b) }}</span></span>
            <span v-if="hours(b)" class="icon-line"><Icon name="schedule" /><span>{{ hours(b) }}</span></span>
            <div class="row">
              <span class="icon-line grow"><Icon name="call" /><span>{{ tr('Calls from the app: ', 'অ্যাপ থেকে ফোন: ') + callsLine(st.get(b.id)) }}</span></span>
              <Button v-if="st.get(b.id)" variant="text" small @click="details = b">{{ tr('Details', 'বিস্তারিত') }}</Button>
            </div>
            <div class="row wrap gap-8">
              <AppealButton v-if="b.status === 'rejected' || b.status === 'suspended'" category="bloodbank" />
              <span class="grow" />
              <!-- আইকনের পাশে লেখা (শুধু আইকন নয়) -->
              <Button variant="outline" small icon="edit" @click="nav(`/work/bloodbank/${b.id}`)">{{ tr('Edit', 'বদলান') }}</Button>
              <!-- মোছা মূল বোতামের পাশে নয় — "আরও"-তে (আমার কাজ CONTRACT §২) -->
              <RowMenu :items="[b.status !== 'suspended' && { label: tr('Remove this blood bank', 'এই ব্লাড ব্যাংক সরিয়ে দিন'), icon: 'delete', danger: true, testid: 'bb-remove', onClick: () => askDel(b) }]" />
            </div>
          </article>
        </div>
      </div>
    </div>
    <ConfirmDialog
      v-if="del"
      :title="tr('Remove this blood bank?', 'এই ব্লাড ব্যাংক সরিয়ে দেবেন?')"
      :text="del.name"
      :confirm="tr('Remove', 'মুছে ফেলুন')"
      danger
      :busy="delBusy"
      :error="delErr"
      @confirm="remove"
      @close="del = null"
    />
    <StatsDialog v-if="details && st.get(details.id)" :title="details.name" :st="st.get(details.id)!" @close="details = null" />
  </BizPage>
</template>
