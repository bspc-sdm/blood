<script setup lang="ts">
// /work/ambulance — নিজের অ্যাম্বুলেন্স: তালিকা, খালি/ব্যস্ত, হিসাব, যোগ/বদল/সরানো (অ্যাপের MyAmbulancesSection)
import { computed, ref } from 'vue';
import { Button, ConfirmDialog, Icon, IconButton, Tag } from '@/ui/kit';
import { useMedia } from '@/shell/chrome';
import { useSession } from '@/lib/session';
import { useCall } from '@/lib/query';
import { call, errorText } from '@/lib/api';
import { num, tr } from '@/lib/i18n';
import { areaLabel } from '@/lib/geo';
import { timeAgo } from '@/lib/format';
import { useNav } from '@/lib/nav';
import { chartSummary } from '@/sections/services/fare';
import { MAX_AMBULANCES, ambulanceDocs, callsLine, myBusinessTitle, useAfterChange, useAreas, useCatalog, useFlash, type Ambulance, type ListingStats } from './shared';
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
import AmbulanceCalls from './AmbulanceCalls.vue';
import StillAvailable from './StillAvailable.vue';
import AmbReceipts from '../invoices/AmbReceipts.vue';
import { invFeature } from '../invoices/features';
import ProviderPreview from '@/ui/listing/ProviderPreview.vue';
import SwitchPair from '@/ui/listing/SwitchPair.vue';
import { LT } from '@/ui/listing/model';
import { useOwnListings } from './ownListing';
import { useCatalog as useLists } from '@/sections/services/data';

const s = useSession();
const nav = useNav();
const wide = useMedia('(min-width: 1000px)');
const ready = computed(() => s.ready && !s.guest && s.workLoaded);
const list = computed(() => s.work.ambulances as Ambulance[]);
const stats = useCall<{ items: ListingStats[] }>('ambulance_stats', {}, () => ({ enabled: ready.value && list.value.length > 0 }));
const cat = useCatalog('ambulanceType');
const after = useAfterChange();
const [flash, setFlash] = useFlash();
const err = ref<string | null>(null);
const busy = ref<string | null>(null);
const del = ref<Ambulance | null>(null);
const delBusy = ref(false);
const delErr = ref<string | null>(null);
const details = ref<Ambulance | null>(null);
useAreas();
const title = computed(() => myBusinessTitle('ambulance'));
const st = computed(() => new Map((stats.data?.items ?? []).map((x) => [x.id, x])));
const canAdd = computed(() => list.value.length < MAX_AMBULANCES);
const live = computed(() => list.value.filter((a) => a.status === 'approved'));
const names = computed(() => Object.fromEntries(list.value.map((a) => [a.id, a.name])));
// "রোগীরা আপনাকে যেভাবে দেখেন" — প্রতিটা চালু গাড়ির কার্ড, আর "খালি আছি / ট্রিপে আছি" (আগের ambulance_available)
const lists = useLists();
const own = useOwnListings('ambulance', () => lists.value);

async function toggle(a: Ambulance, on: boolean) {
  busy.value = a.id;
  err.value = null;
  setFlash(null);
  try {
    await call('ambulance_available', { id: a.id, available: on });
    own.patch(a, { available: on, availableAt: on ? Date.now() : a.availableAt, busySince: 0 });
    await after();
  } catch (e) {
    err.value = errorText(e);
  } finally {
    busy.value = null;
  }
}
async function remove() {
  if (!del.value) return;
  delBusy.value = true;
  delErr.value = null;
  try {
    await call('ambulance_delete', { id: del.value.id });
    await after();
    del.value = null;
    setFlash(tr('Removed', 'মুছে ফেলা হয়েছে'));
  } catch (e) {
    delErr.value = errorText(e);
  } finally {
    delBusy.value = false;
  }
}
const types = (a: Ambulance) => a.types.map(cat.label).join(' · ');
const area = (a: Ambulance) => [areaLabel(a.district, a.upazilaId), a.area].filter(Boolean).join(' · ');
const askDel = (a: Ambulance) => {
  delErr.value = null;
  del.value = a;
};
</script>

<template>
  <BizPage v-if="!ready" :title="title"><WorkGate /></BizPage>
  <BizPage v-else :title="title">
    <template v-if="canAdd" #actions><Button icon="add" @click="nav('/work/ambulance/new')">{{ tr('Add ambulance', 'অ্যাম্বুলেন্স যোগ করুন') }}</Button></template>
    <Flash :msg="flash" :error="err" />
    <ProviderPreview v-for="a in live" :key="'pv-' + a.id" :l="own.listing(a)" :public-to="`/services/ambulance/${a.id}`" :store-key="'ambulance.' + a.id">
      <SwitchPair
        :label="LT.ambFree()"
        testid="sw-available"
        :busy="busy === a.id"
        :value="a.available ? 'free' : 'trip'"
        :options="[{ key: 'free', label: LT.ambFree(), icon: 'check_circle', good: true }, { key: 'trip', label: LT.ambTrip(), icon: 'ambulance' }]"
        @pick="(k) => toggle(a, k === 'free')"
      />
    </ProviderPreview>
    <!-- উপরে "ডাক" (২.২৮.০): রোগীর "অ্যাম্বুলেন্স ডাকুন" -->
    <AmbulanceCalls v-if="live.length" :names="names" :on-changed="() => { after(); }" />
    <div v-if="list.length" class="stats work-stats">
      <Stat icon="ambulance" :value="num(list.length)" :label="tr('Ambulances', 'অ্যাম্বুলেন্স')" />
      <Stat icon="check_circle" :value="num(live.filter((a) => a.available).length)" :label="tr('Available now', 'এখন খালি')" />
      <Stat icon="schedule" :value="num(list.filter((a) => a.status === 'pending').length)" :label="tr('Under review', 'যাচাই চলছে')" />
      <Stat icon="call" :value="stats.data ? num((stats.data.items ?? []).reduce((n, x) => n + x.calls[30], 0)) : '—'" :label="tr('Calls from the app · 30 days', 'অ্যাপ থেকে ফোন · ৩০ দিনে')" />
    </div>
    <DocsNeededNotice v-for="a in list" :key="a.id" kind="ambulance" :id="a.id" :specs="ambulanceDocs()" :item="a" :title="`${a.name} · ${a.vehicleNo}`" @done="after" />

    <WorkEmpty v-if="!list.length" icon="ambulance" :title="tr('No ambulance added yet', 'এখনো কোনো অ্যাম্বুলেন্স যোগ করা হয়নি')">
      <Button icon="add" @click="nav('/work/ambulance/new')">{{ tr('Add ambulance', 'অ্যাম্বুলেন্স যোগ করুন') }}</Button>
    </WorkEmpty>
    <div v-else-if="wide" class="table-wrap">
      <table class="table work-table">
        <thead>
          <tr>
            <th>{{ tr('Vehicle', 'গাড়ি') }}</th>
            <th>{{ tr('Type', 'ধরন') }}</th>
            <th>{{ tr('Area', 'এলাকা') }}</th>
            <th>{{ tr('Status', 'অবস্থা') }}</th>
            <th>{{ tr('Right now', 'এখনকার অবস্থা') }}</th>
            <th>{{ tr('Calls from the app', 'অ্যাপ থেকে ফোন') }}</th>
            <th :aria-label="tr('Actions', 'কাজ')" />
          </tr>
        </thead>
        <tbody>
          <tr v-for="a in list" :key="a.id">
            <td>
              <div class="col gap-2">
                <span class="semi">{{ a.name }}</span>
                <span class="t-body-s muted">{{ a.vehicleNo }}</span>
              </div>
            </td>
            <td>{{ types(a) }}</td>
            <td class="work-td-area">{{ area(a) }}</td>
            <td>
              <div class="col gap-4">
                <StatusTag :status="a.status" />
                <StatusReason :status="a.status" :reason="a.statusReason" />
                <AppealButton v-if="a.status === 'rejected' || a.status === 'suspended'" category="ambulance" />
              </div>
            </td>
            <td>
              <div class="col gap-2">
                <label v-if="a.status === 'approved'" class="work-avail">
                  <input type="checkbox" role="switch" class="switch" :checked="a.available" :disabled="busy === a.id" @change="toggle(a, ($event.target as HTMLInputElement).checked)" />
                  <span class="t-body-m">{{ a.available ? tr('Available now', 'এখন খালি আছি') : tr('On a trip / busy', 'ট্রিপে আছি / ব্যস্ত') }}</span>
                </label>
                <span v-else class="muted">—</span>
                <span v-if="a.status === 'approved' && a.available && a.availableAt > 1" class="t-body-s muted">{{ tr('Marked available ', 'খালি জানিয়েছেন ') + timeAgo(a.availableAt) }}</span>
                <StillAvailable :a="a" :busy="busy === a.id" @renew="toggle(a, true)" />
              </div>
            </td>
            <td>
              <div class="col gap-2">
                <span class="num">{{ st.get(a.id) ? callsLine(st.get(a.id)) : '—' }}</span>
                <button v-if="st.get(a.id)" type="button" class="work-link" @click="details = a">{{ tr('Details', 'বিস্তারিত') }}</button>
              </div>
            </td>
            <td>
              <div class="row gap-4 work-row-actions">
                <IconButton icon="edit" :label="tr('Edit', 'বদলান')" @click="nav(`/work/ambulance/${a.id}`)" />
                <IconButton v-if="a.status !== 'suspended'" icon="delete" :label="tr('Remove this ambulance', 'এই অ্যাম্বুলেন্স সরিয়ে দিন')" @click="askDel(a)" />
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <div v-else class="work-cards">
      <article v-for="a in list" :key="a.id" class="card col gap-8">
        <div class="row top">
          <div class="col gap-2 grow">
            <span class="t-title-m">{{ a.name }}</span>
            <span class="t-body-s muted">{{ a.vehicleNo }}</span>
          </div>
          <StatusTag :status="a.status" />
        </div>
        <StatusReason :status="a.status" :reason="a.statusReason" />
        <div class="row wrap gap-6"><Tag v-for="t in a.types" :key="t" tone="primary">{{ cat.label(t) }}</Tag></div>
        <span v-if="area(a)" class="icon-line"><Icon name="location_on" /><span>{{ area(a) }}</span></span>
        <span v-if="a.fareChart" class="icon-line"><Icon name="payments" /><span>{{ tr('Fare: ', 'ভাড়া: ') + chartSummary(a.fareChart) }}</span></span>
        <span v-if="a.fare" class="icon-line"><Icon name="edit_note" /><span>{{ a.fare }}</span></span>
        <div v-if="a.status === 'approved'" class="col gap-2">
          <label class="work-avail">
            <input type="checkbox" role="switch" class="switch" :checked="a.available" :disabled="busy === a.id" @change="toggle(a, ($event.target as HTMLInputElement).checked)" />
            <span class="t-body-m">{{ a.available ? tr('Available now', 'এখন খালি আছি') : tr('On a trip / busy', 'ট্রিপে আছি / ব্যস্ত') }}</span>
          </label>
          <StillAvailable :a="a" :busy="busy === a.id" @renew="toggle(a, true)" />
          <span class="t-body-s muted">{{ tr('Turn off when you go on a trip.', 'ট্রিপে গেলে বন্ধ করুন।') }}</span>
        </div>
        <div class="row">
          <span class="icon-line grow"><Icon name="call" /><span>{{ tr('Calls from the app: ', 'অ্যাপ থেকে ফোন: ') + callsLine(st.get(a.id)) }}</span></span>
          <Button v-if="st.get(a.id)" variant="text" small @click="details = a">{{ tr('Details', 'বিস্তারিত') }}</Button>
        </div>
        <div class="row wrap gap-8">
          <AppealButton v-if="a.status === 'rejected' || a.status === 'suspended'" category="ambulance" />
          <span class="grow" />
          <div class="row gap-4 work-row-actions">
            <IconButton icon="edit" :label="tr('Edit', 'বদলান')" @click="nav(`/work/ambulance/${a.id}`)" />
            <IconButton v-if="a.status !== 'suspended'" icon="delete" :label="tr('Remove this ambulance', 'এই অ্যাম্বুলেন্স সরিয়ে দিন')" @click="askDel(a)" />
          </div>
        </div>
      </article>
    </div>
    <p v-if="wide && live.length" class="t-body-s muted">{{ tr('Turn off when you go on a trip.', 'ট্রিপে গেলে বন্ধ করুন।') }}</p>

    <ConfirmDialog
      v-if="del"
      :title="tr('Remove this ambulance?', 'এই অ্যাম্বুলেন্স সরিয়ে দেবেন?')"
      :text="`${del.name} · ${del.vehicleNo}`"
      :confirm="tr('Remove', 'মুছে ফেলুন')"
      danger
      :busy="delBusy"
      :error="delErr"
      @confirm="remove"
      @close="del = null"
    />
    <StatsDialog v-if="details && st.get(details.id)" :title="`${details.name} · ${details.vehicleNo}`" :st="st.get(details.id)!" @close="details = null" />
    <AmbReceipts v-if="invFeature('inv_ambulance')" :cars="live" />
  </BizPage>
</template>
