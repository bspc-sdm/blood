<script setup lang="ts">
// দোকান: কাছের মানুষ কী খুঁজছেন আর কতটা লাগবে — আছে হলে কতটা আছে লিখে পাঠান, না থাকলে কিছু না করলেই চলে
// (বা চুপচাপ "বাদ দিন") — FindAnswer.vue, backend/docs/MEDFIND.md
import { computed, ref } from 'vue';
import { ErrorText, Loading, Switch } from '@/ui/kit';
import { useMedia } from '@/shell/chrome';
import { useCall } from '@/lib/query';
import { call, errorText } from '@/lib/api';
import { tr } from '@/lib/i18n';
import { formatKm, timeAgo } from '@/lib/format';
import { needText, unitsFor } from '@/sections/services/medfind';
import FindAnswer from './FindAnswer.vue';

interface FindReq {
  id: string; drug: { name: string; generic?: string; form?: string; company?: string }; area: string; km: number | null; answer: 'yes' | 'no' | null; createdAt: number;
  qty?: number | null; unit?: string | null; haveQty?: number | null; haveUnit?: string | null;
}

const q = useCall<{ items: FindReq[]; findOff: boolean }>('med_find_requests', {}, { refetchInterval: 60_000 });
const wide = useMedia('(min-width: 1000px)');
const busyId = ref<string | null>(null);
const err = ref<string | null>(null);
const switching = ref(false);
const off = computed(() => q.data?.findOff ?? false);
const list = computed(() => q.data?.items);

async function answer(r: FindReq, body: { haveQty: number } | { dismiss: true }) {
  busyId.value = r.id;
  err.value = null;
  try {
    await call('med_find_answer', { id: r.id, ...body });
    await q.refetch();
  } catch (e) {
    err.value = errorText(e);
  } finally {
    busyId.value = null;
  }
}
async function setOn(on: boolean) {
  switching.value = true;
  err.value = null;
  try {
    await call('pharmacy_find_setting', { off: !on });
    await q.refetch();
  } catch (e) {
    err.value = errorText(e);
  } finally {
    switching.value = false;
  }
}
// ক্রেতার একক (পরিমাণ ছাড়া পুরোনো খোঁজে ওষুধের ধরনের স্বাভাবিক একক — সার্ভারও তাই রাখে)
const unitOf = (r: FindReq) => r.haveUnit ?? r.unit ?? unitsFor(r.drug.form)[0];
const drugLine = (r: FindReq) => r.drug.name + (r.drug.form ? ' · ' + r.drug.form : '');
const meta = (r: FindReq) => [r.area || null, r.km != null ? formatKm(r.km) : null, timeAgo(r.createdAt)].filter(Boolean).join(' · ');
</script>

<template>
  <section class="card col gap-8">
    <Switch :model-value="!off" :disabled="switching || !q.data" :sub="off ? tr('Turned off — you won\'t get requests.', 'বন্ধ — অনুরোধ আসবে না।') : undefined" @update:model-value="setOn">
      <template #label><span class="t-title-m">{{ tr('Medicine requests', 'ওষুধ খোঁজার অনুরোধ') }}</span></template>
    </Switch>
    <ErrorText :text="err ?? q.error?.message" />
    <template v-if="!off">
      <template v-if="!list"><Loading v-if="!q.error" /></template>
      <p v-else-if="!list.length" class="t-body-m muted">{{ tr('No open requests now.', 'এখন কোনো খোলা অনুরোধ নেই।') }}</p>
      <div v-else-if="wide" class="table-wrap work-inner-table">
        <table class="table">
          <thead>
            <tr>
              <th>{{ tr('Medicine', 'ওষুধ') }}</th>
              <th>{{ tr('Area', 'এলাকা') }}</th>
              <th>{{ tr('Your answer', 'আপনার উত্তর') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="r in list" :key="r.id">
              <td>
                <div class="col gap-2">
                  <span class="semi">{{ drugLine(r) }}</span>
                  <span v-if="r.drug.generic" class="t-body-s muted">{{ r.drug.generic }}</span>
                  <span v-if="r.qty" class="t-body-m medfind-need" data-testid="find-need">{{ needText(r.qty, r.unit) }}</span>
                </div>
              </td>
              <td class="t-body-s muted">{{ meta(r) }}</td>
              <td>
                <FindAnswer :unit="unitOf(r)" :have-qty="r.haveQty ?? null" :busy="busyId === r.id" :disabled="busyId != null" @answer="(n) => answer(r, { haveQty: n })" @dismiss="answer(r, { dismiss: true })" />
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-else class="col gap-8">
        <div v-for="r in list" :key="r.id" class="work-find">
          <span class="semi">{{ drugLine(r) }}</span>
          <span v-if="r.drug.generic" class="t-body-s">{{ r.drug.generic }}</span>
          <span v-if="r.qty" class="t-body-m medfind-need" data-testid="find-need">{{ needText(r.qty, r.unit) }}</span>
          <span class="t-body-s muted">{{ meta(r) }}</span>
          <FindAnswer :unit="unitOf(r)" :have-qty="r.haveQty ?? null" :busy="busyId === r.id" :disabled="busyId != null" @answer="(n) => answer(r, { haveQty: n })" @dismiss="answer(r, { dismiss: true })" />
        </div>
      </div>
    </template>
  </section>
</template>
