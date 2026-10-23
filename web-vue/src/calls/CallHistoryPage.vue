<script setup lang="ts">
// কলের তালিকা (/calls): দুই দিকের শেষ কলগুলো (call_history), নিচে "আরও দেখুন" (before = শেষটার সময়)।
// প্রতিটা সারি চাপলে সেই প্রসঙ্গের পাতা (রিকোয়েস্ট, অ্যাপয়েন্টমেন্ট …)।
import { computed, ref, watch } from 'vue';
import { Button, EmptyState, ErrorBox, IconButton, SkeletonCards } from '@/ui/kit';
import Icon from '@/ui/Icon.vue';
import FormPage from '@/shell/FormPage.vue';
import { errorText } from '@/lib/api';
import { formatDateTime } from '@/lib/format';
import { tr } from '@/lib/i18n';
import { useSession } from '@/lib/session';
import { useNav } from '@/lib/nav';
import { callHistory, type HistoryItem } from './api';
import { T, clock, ctxText, stateText } from './text';
import FlagActions from './FlagActions.vue';
import { comingSoon, featureOn } from '@/lib/features';

const s = useSession();
const nav = useNav();
const title = computed(() => T.history());

const items = ref<HistoryItem[]>([]);
const next = ref<number | null>(null);
const loading = ref(false);
const err = ref<string | null>(null);
async function load(more = false) {
  loading.value = true;
  err.value = null;
  try {
    const r = await callHistory(more ? next.value ?? undefined : undefined);
    const have = new Set(more ? items.value.map((x) => x.callId) : []);
    items.value = [...(more ? items.value : []), ...r.items.filter((x) => !have.has(x.callId))];
    next.value = r.items.length ? r.next : null;
  } catch (e) {
    err.value = errorText(e);
  } finally {
    loading.value = false;
  }
}
watch(() => (s.ready && !s.guest && featureOn('calls_audio') ? s.uid : null), (u) => u && load(), { immediate: true });

const missed = (x: HistoryItem) => x.state === 'missed' || (x.dir === 'in' && x.state === 'declined');
const icon = (x: HistoryItem) => (missed(x) ? 'phone_missed' : x.dir === 'in' ? 'call_received' : x.dir === 'out' ? 'call_made' : x.video ? 'videocam' : 'call');
const line = (x: HistoryItem) =>
  [x.dir === 'in' ? T.dirIn() : x.dir === 'out' ? T.dirOut() : null, x.video ? T.video() : T.audioCall(), ctxText(x.ctx), x.durationSec ? clock(x.durationSec) : stateText(x.state)]
    .filter(Boolean)
    .join(' · ');
function target(x: HistoryItem): string | null {
  if (!x.refId) return null;
  switch (x.ctx) {
    case 'blood': return x.dir === 'out' ? `/my-requests/${encodeURIComponent(x.refId)}` : `/request/${encodeURIComponent(x.refId)}`;
    case 'appointment': return '/my-appointments';
    case 'ambulance': return x.dir === 'out' ? `/services/ambulance/call/${encodeURIComponent(x.refId)}` : '/work';
    case 'rx_order': return x.dir === 'in' ? '/work/pharmacy' : '/services/pharmacy/prescriptions';
    case 'lab_order': return x.dir === 'in' ? '/work/lab' : '/services/pharmacy/prescriptions';
    case 'order': return '/services/pharmacy/prescriptions';
  }
  return null;
}
</script>

<template>
  <FormPage :title="title" back-to="/profile" :boxed="false">
  <div class="col gap-12" data-testid="call-history">
    <div class="row">
      <span class="grow" />
      <IconButton icon="refresh" :label="tr('Refresh', 'রিফ্রেশ')" :disabled="loading" @click="load()" />
    </div>
    <p class="t-body-s muted">{{ T.historySub() }}</p>
    <EmptyState v-if="!featureOn('calls_audio')" icon="call" :title="comingSoon()" data-testid="feature-soon" />
    <EmptyState v-else-if="s.ready && s.guest" icon="call" :title="tr('Log in to do this', 'এর জন্য লগইন করুন')">
      <Button @click="s.login()">{{ tr('Log in', 'লগইন') }}</Button>
    </EmptyState>
    <SkeletonCards v-else-if="loading && !items.length" :n="5" :h="60" />
    <ErrorBox v-else-if="err && !items.length" :error="err" :on-retry="() => load()" />
    <EmptyState v-else-if="!items.length" icon="call" :title="T.historyEmpty()" />
    <template v-else>
      <ul class="call-list">
        <li v-for="x in items" :key="x.callId" class="call-row" data-testid="call-row" :data-state="x.state">
          <span :class="['ic', missed(x) && 'missed']"><Icon :name="icon(x)" /></span>
          <button type="button" class="col gap-2 grow" :style="{ all: 'unset', cursor: target(x) ? 'pointer' : 'default', flex: '1', minWidth: 0 }" :disabled="!target(x)" @click="target(x) && nav(target(x)!)">
            <span class="t-title-s ellipsis">{{ x.peer.name || '—' }}</span>
            <span class="t-body-s muted">{{ line(x) }}</span>
            <span class="t-label-s muted">{{ x.startedAt ? formatDateTime(x.startedAt) : '' }}</span>
          </button>
          <FlagActions v-if="x.peer.uid && x.ctx !== 'appointment'" :peer-uid="x.peer.uid" :peer-name="x.peer.name" :call-id="x.callId" />
        </li>
      </ul>
      <Button v-if="next" variant="outline" :busy="loading" @click="load(true)">{{ T.more() }}</Button>
    </template>
  </div>
  </FormPage>
</template>
