<script setup lang="ts">
// দোকান: কাছের মানুষ কী খুঁজছেন — এক চাপে "আছে"/"নেই"
import { computed, ref } from 'vue';
import { Button, ErrorText, Loading, Switch } from '@/ui/kit';
import { useMedia } from '@/shell/chrome';
import { useCall } from '@/lib/query';
import { call, errorText } from '@/lib/api';
import { tr } from '@/lib/i18n';
import { formatKm, timeAgo } from '@/lib/format';

interface FindReq { id: string; drug: { name: string; generic?: string; form?: string; company?: string }; area: string; km: number | null; answer: 'yes' | 'no' | null; createdAt: number }

const q = useCall<{ items: FindReq[]; findOff: boolean }>('med_find_requests', {}, { refetchInterval: 60_000 });
const wide = useMedia('(min-width: 1000px)');
const busyId = ref<string | null>(null);
const err = ref<string | null>(null);
const switching = ref(false);
const off = computed(() => q.data?.findOff ?? false);
const list = computed(() => q.data?.items);

async function answer(r: FindReq, have: boolean) {
  busyId.value = r.id;
  err.value = null;
  try {
    await call('med_find_answer', { id: r.id, have });
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
                </div>
              </td>
              <td class="t-body-s muted">{{ meta(r) }}</td>
              <td>
                <div v-if="r.answer === 'yes'" class="row wrap gap-8">
                  <span class="t-label-l c-primary">{{ tr('You said: have it', 'আপনার উত্তর: আছে') }}</span>
                  <Button variant="text" small :disabled="busyId != null" @click="answer(r, false)">{{ tr('Change to no', '"নেই" করুন') }}</Button>
                </div>
                <div v-else class="row wrap gap-8">
                  <Button small :busy="busyId === r.id" :disabled="busyId != null" @click="answer(r, true)">{{ tr('Have it', 'আছে') }}</Button>
                  <Button variant="outline" small :disabled="busyId != null || r.answer === 'no'" @click="answer(r, false)">
                    {{ r.answer === 'no' ? tr('Said no', '"নেই" বলেছেন') : tr('Don\'t have', 'নেই') }}
                  </Button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-else class="col gap-8">
        <div v-for="r in list" :key="r.id" class="work-find">
          <span class="semi">{{ drugLine(r) }}</span>
          <span v-if="r.drug.generic" class="t-body-s">{{ r.drug.generic }}</span>
          <span class="t-body-s muted">{{ meta(r) }}</span>
          <div v-if="r.answer === 'yes'" class="row wrap gap-8">
            <span class="t-label-l c-primary">{{ tr('You said: have it', 'আপনার উত্তর: আছে') }}</span>
            <Button variant="text" small :disabled="busyId != null" @click="answer(r, false)">{{ tr('Change to no', '"নেই" করুন') }}</Button>
          </div>
          <div v-else class="row wrap gap-8">
            <Button small :busy="busyId === r.id" :disabled="busyId != null" @click="answer(r, true)">{{ tr('Have it', 'আছে') }}</Button>
            <Button variant="outline" small :disabled="busyId != null || r.answer === 'no'" @click="answer(r, false)">
              {{ r.answer === 'no' ? tr('Said no', '"নেই" বলেছেন') : tr('Don\'t have', 'নেই') }}
            </Button>
          </div>
        </div>
      </div>
    </template>
  </section>
</template>
