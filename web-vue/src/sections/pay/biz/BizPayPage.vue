<script setup lang="ts">
// ব্যবসার "পেমেন্ট" (/work/payments): কোন ব্যবসা (?biz=kind:id), তারপর ভাগ —
//   /work/payments           মেলানো বাকি: আজকের হিসাব + রোগী জানানো পেমেন্ট ("পেয়েছি" তিন টিকে / "পাইনি")
//   /work/payments/all       সব পেমেন্ট: অবস্থা আর সময় দিয়ে ছাঁকা, ফেরত, রসিদ, মাসের CSV (মালিক)
//   /work/payments/settings  নম্বর (২৪ ঘণ্টার অপেক্ষা, আবার লগইন, "আমি করিনি") আর ডাক্তারের অগ্রিম (মালিক)
// সুইচ: pay_manual (+ ধরনের চাবি) বা ডাক্তারের online_chamber (features.ts bizKindOn) — কোনোটা না থাকলে "শিগগির আসছে"।
import { computed, ref } from 'vue';
import { useRoute } from 'vue-router';
import { Button, EmptyState, ErrorText, PillTabs, Select } from '@/ui/kit';
import { errorText } from '@/lib/api';
import { comingSoon } from '@/lib/features';
import { matchPath, useNav, useSearchParams } from '@/lib/nav';
import { useSession } from '@/lib/session';
import PartnerPage from '../../rx/partner/PartnerPage.vue';
import { monthText, shiftMonth, thisMonth } from '../../invoices/model';
import * as api from '../api';
import { anyBizPayOn } from '../features';
import { usePayLive } from '../live';
import { T, kindText } from '../text';
import { bizKey, useMyPayBiz } from './bizlist';
import PayList from './PayList.vue';
import PaySummaryCard from './PaySummaryCard.vue';
import PaySettings from './PaySettings.vue';

const BASE = '/work/payments';
const route = useRoute();
const nav = useNav();
const [sp, setSp] = useSearchParams();
const s = useSession();
usePayLive();
const list = useMyPayBiz();
const tab = computed(() => (matchPath({ path: `${BASE}/settings`, end: true }, route.path) ? 'settings' : matchPath({ path: `${BASE}/all`, end: true }, route.path) ? 'all' : 'inbox'));
const cur = computed(() => list.value.find((b) => bizKey(b) === sp.value.get('biz')) ?? list.value[0] ?? null);
const go = (k: string) => nav(`${BASE}${k === 'inbox' ? '' : '/' + k}${cur.value ? `?biz=${encodeURIComponent(bizKey(cur.value))}` : ''}`);
const pickBiz = (key: string) => setSp({ biz: key }, { replace: true });
const tabs = computed(() => [
  { key: 'inbox', label: T.inbox(), icon: 'payments' },
  { key: 'all', label: T.all(), icon: 'list_alt' },
  ...(cur.value?.owner || cur.value?.kind === 'chamber' ? [{ key: 'settings', label: T.settings(), icon: 'settings_outline' }] : []),
]);

// মাসের CSV (মালিক) — pay_export_csv {csv, filename}
const months = [0, -1, -2].map((d) => shiftMonth(thisMonth(), d));
const month = ref(months[0]);
const csvBusy = ref(false);
const csvErr = ref<string | null>(null);
async function csv() {
  if (!cur.value) return;
  csvBusy.value = true;
  csvErr.value = null;
  try {
    const r = await api.exportCsv(cur.value.kind, cur.value.bizId, month.value);
    if (r.url) return void window.open(r.url, '_blank', 'noopener');
    const blob = new Blob(['﻿' + (r.csv ?? '').replace(/^﻿/, '')], { type: 'text/csv;charset=utf-8' });
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = r.filename || `lifolo-pay-${cur.value.kind}-${month.value}.csv`;
    document.body.append(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(a.href), 10_000);
  } catch (e) {
    csvErr.value = errorText(e);
  } finally {
    csvBusy.value = false;
  }
}
</script>

<template>
  <PartnerPage :title="T.bizTitle()" back-to="/work">
    <div class="col gap-12 pay-biz" data-testid="biz-pay-page">
      <EmptyState v-if="!anyBizPayOn()" icon="payments" :title="comingSoon()" data-testid="feature-off" />
      <EmptyState v-else-if="s.workLoaded && !list.length" icon="payments" :title="T.noBiz()" />
      <template v-else-if="cur">
        <Select
          v-if="list.length > 1"
          :label="T.whichBiz()"
          :model-value="bizKey(cur)"
          :options="list.map((b) => ({ value: bizKey(b), label: kindText(b.kind) + ' · ' + b.name }))"
          data-testid="pay-biz-pick"
          @update:model-value="pickBiz"
        />
        <PillTabs :label="T.bizTitle()" :value="tab" :tabs="tabs" @change="go" />
        <template v-if="tab === 'inbox'">
          <PaySummaryCard :key="bizKey(cur) + ':s'" :kind="cur.kind" :biz-id="cur.bizId" />
          <section class="card col gap-8">
            <h2 class="t-title-m">{{ T.pendingClaims() }}</h2>
            <PayList :key="bizKey(cur) + ':i'" :kind="cur.kind" :biz-id="cur.bizId" :owner="cur.owner" :extra="cur.extra" inbox />
          </section>
        </template>
        <template v-else-if="tab === 'all'">
          <section class="card col gap-8">
            <PayList :key="bizKey(cur) + ':a'" :kind="cur.kind" :biz-id="cur.bizId" :owner="cur.owner" :extra="cur.extra" />
          </section>
          <div v-if="cur.owner" class="row wrap gap-8" data-testid="pay-csv">
            <Select v-model="month" :aria-label="T.csvMonth()" :options="months.map((m) => ({ value: m, label: monthText(m) }))" />
            <Button small variant="outline" icon="list_alt" :busy="csvBusy" data-testid="pay-csv-export" @click="csv">{{ T.csv() }}</Button>
            <ErrorText :text="csvErr" />
          </div>
        </template>
        <PaySettings v-else :key="bizKey(cur) + ':set'" :kind="cur.kind" :biz-id="cur.bizId" :owner="cur.owner" />
      </template>
    </div>
  </PartnerPage>
</template>
