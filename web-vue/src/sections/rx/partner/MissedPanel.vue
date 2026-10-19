<script setup lang="ts">
// ক্রেতা চেয়েও পাননি এমন ওষুধ — স্টকে আনলে "এনেছি"
import { computed, ref } from 'vue';
import { useQueryClient } from '@tanstack/vue-query';
import { Button, ConfirmDialog, ErrorText, Loading, toast } from '@/ui/kit';
import { call, errorText } from '@/lib/api';
import { useCall } from '@/lib/query';
import { num, tr } from '@/lib/i18n';
import { formatDayOf } from '@/lib/format';
import { useInvalidate } from './common';
import type { PharmacyHome } from './model';
import Panel from './Panel.vue';

const home = useCall<PharmacyHome>('rx_pharmacy_home', {});
const qc = useQueryClient();
const invalidate = useInvalidate();
const busyK = ref<string | null>(null);
const err = ref<string | null>(null);
const all = ref(false);
const clearing = ref(false);
const list = computed(() => home.data?.missed ?? []);
const shown = computed(() => (all.value ? list.value : list.value.slice(0, 6)));
const drop = (k: string | null) =>
  qc.setQueriesData<PharmacyHome>({ predicate: (q) => q.queryKey[0] === 'rx_pharmacy_home' }, (d) => (d ? { ...d, missed: k ? d.missed.filter((m) => m.k !== k) : [] } : d));

async function got(k: string, name: string) {
  busyK.value = k;
  err.value = null;
  try {
    await call('rx_missed_clear', { k });
    await call('stock_set', { add: [name], remove: [] });
    drop(k);
    invalidate('stock_get');
    toast(tr('Added to stock', 'স্টকে যোগ হয়েছে'));
  } catch (e) {
    err.value = errorText(e);
  } finally {
    busyK.value = null;
  }
}
async function clearAll() {
  busyK.value = '*';
  err.value = null;
  try {
    await call('rx_missed_clear', {});
    drop(null);
    clearing.value = false;
  } catch (e) {
    err.value = errorText(e);
  } finally {
    busyK.value = null;
  }
}
</script>

<template>
  <Panel :title="tr('Asked for but not available', 'চেয়েও পাননি এমন ওষুধ')" icon="list_alt" :count="list.length ? num(list.length) : undefined">
    <p class="t-body-s muted">{{ tr('Medicines customers came for but you couldn\'t give. Keep them in stock to sell more.', 'ক্রেতা প্রেসক্রিপশন নিয়ে এসে যে ওষুধ পাননি। স্টকে রাখলে বিক্রি বাড়বে।') }}</p>
    <Loading v-if="home.isLoading" />
    <ErrorText v-else-if="home.error" :text="errorText(home.error)" />
    <p v-else-if="!list.length" class="t-body-m muted">{{ tr('Nothing yet', 'এখনো কিছু নেই') }}</p>
    <ul v-else class="rxp-missed">
      <li v-for="m in shown" :key="m.k">
        <div class="col gap-2 grow">
          <span class="semi">{{ m.name }}</span>
          <span class="t-body-s muted">{{ tr(`${m.count} times · last `, `${num(m.count)} বার · শেষ `) }}{{ formatDayOf(m.lastAt) }}</span>
        </div>
        <Button small variant="tonal" icon="check_circle" :busy="busyK === m.k" :disabled="!!busyK" @click="got(m.k, m.name)">{{ tr('Got it in stock', 'এনেছি') }}</Button>
      </li>
    </ul>
    <div class="row wrap gap-8">
      <Button v-if="list.length > 6" variant="text" small @click="all = !all">{{ all ? tr('Show less', 'কম দেখান') : tr(`See all (${list.length})`, `সব দেখুন (${num(list.length)})`) }}</Button>
      <span class="grow" />
      <Button v-if="list.length" variant="danger-text" small :disabled="!!busyK" @click="clearing = true">{{ tr('Clear list', 'তালিকা মুছুন') }}</Button>
    </div>
    <ErrorText :text="err" />
    <ConfirmDialog
      v-if="clearing"
      :title="tr('Clear the whole list?', 'পুরো তালিকা মুছবেন?')"
      :text="tr('It starts counting again from the next prescription. Your stock list doesn\'t change.', 'পরের প্রেসক্রিপশন থেকে আবার গোনা শুরু হবে। স্টকের তালিকা বদলাবে না।')"
      :confirm="tr('Clear', 'মুছুন')"
      danger
      :busy="busyK === '*'"
      :error="err"
      @confirm="clearAll"
      @close="clearing = false"
    />
  </Panel>
</template>
