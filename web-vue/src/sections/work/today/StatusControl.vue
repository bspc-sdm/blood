<script setup lang="ts">
// একটা ব্যবসার অবস্থা: খোলা / বিরতি / বন্ধ (অ্যাম্বুলেন্স: খালি আছি / ট্রিপে আছি)। বিরতি: ৩০ মিনিট · ১ ঘণ্টা · আজকের মতো —
// শেষে নিজে খোলে (সার্ভারের `until`), কমলা লেখায় "আবার খুলবে …"; বন্ধ লাল। চাপলে সাথে সাথে বাছা দেখায়, তারপর সার্ভার।
// অ্যাম্বুলেন্স: "খালি আছি"-র মেয়াদের শেষ ঘণ্টায় "আবার চালু রাখুন" ("এখনো খালি আছেন?" নোটিফিকেশন এখানে আনে) — খালি/ট্রিপে শুধু এখানে।
import { computed, ref, watch } from 'vue';
import { Button, ErrorText, Icon } from '@/ui/kit';
import { call, errorText } from '@/lib/api';
import { formatTime } from '@/lib/format';
import StillAvailable from '../StillAvailable.vue';
import { TT } from './text';
import { hasPause, nextDhakaMidnight, statusCall, type PauseFor, type Status, type TodayBiz } from './model';

const props = defineProps<{ biz: TodayBiz }>();
const emit = defineEmits<{ changed: [] }>();
const want = ref<Status | null>(null);
const asking = ref(false);
const busy = ref(false);
const err = ref<string | null>(null);
watch(
  () => [props.biz.status, props.biz.pausedUntil],
  () => (want.value = null),
);
const cur = computed(() => want.value ?? props.biz.status);
const amb = computed(() => props.biz.kind === 'ambulance');
const opts = computed(() =>
  amb.value
    ? [
        { key: 'open' as Status, label: TT.free(), icon: 'ambulance' },
        { key: 'closed' as Status, label: TT.onTrip(), icon: 'directions_run' },
      ]
    : [
        { key: 'open' as Status, label: TT.open(), icon: 'storefront' },
        ...(hasPause(props.biz) ? [{ key: 'paused' as Status, label: TT.paused(), icon: 'pause' }] : []),
        { key: 'closed' as Status, label: TT.closed(), icon: 'close' },
      ],
);
const note = computed(() => {
  if (amb.value) return null;
  if (cur.value === 'paused') {
    const u = props.biz.pausedUntil;
    if (!u) return { tone: 'warn', text: TT.paused() };
    return { tone: 'warn', text: u >= nextDhakaMidnight() ? TT.reopensTomorrow() : TT.reopensAt(formatTime(u)) };
  }
  if (cur.value === 'closed') return { tone: 'err', text: TT.closedNote() };
  return null;
});

async function set(to: Status, pause?: PauseFor) {
  asking.value = false;
  if (to === cur.value && to !== 'paused') return;
  const [action, body] = statusCall(props.biz, to, pause);
  want.value = to;
  busy.value = true;
  err.value = null;
  try {
    await call(action, body);
    emit('changed');
  } catch (e) {
    want.value = null;
    err.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
/** "আবার চালু রাখুন": আবার ১২ ঘণ্টা খালি */
async function renew() {
  busy.value = true;
  err.value = null;
  try {
    await call('ambulance_available', { id: props.biz.id, available: true });
    emit('changed');
  } catch (e) {
    err.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
function pick(k: Status) {
  if (k === 'paused') asking.value = !asking.value;
  else set(k);
}
</script>

<template>
  <div class="col gap-6 td-status" :data-status="cur ?? ''">
    <div class="td-seg" role="group" :aria-label="TT.statusOf(biz.name)" :data-testid="`status-${biz.kind}-${biz.id}`">
      <button
        v-for="o in opts"
        :key="o.key"
        type="button"
        :class="['td-seg-btn', o.key]"
        :aria-pressed="cur === o.key"
        :aria-expanded="o.key === 'paused' ? asking : undefined"
        :disabled="busy"
        :data-key="o.key"
        @click="pick(o.key)"
      >
        <Icon :name="cur === o.key ? 'check_circle' : o.icon" />{{ o.label }}
      </button>
    </div>
    <div v-if="asking" class="td-pause" role="group" :aria-label="TT.pauseFor()" data-testid="pause-options">
      <span class="t-label-l">{{ TT.pauseFor() }}</span>
      <div class="row wrap gap-6">
        <Button variant="tonal" small @click="set('paused', 30)">{{ TT.p30() }}</Button>
        <Button variant="tonal" small @click="set('paused', 60)">{{ TT.p60() }}</Button>
        <Button variant="tonal" small @click="set('paused', 'day')">{{ TT.pDay() }}</Button>
        <Button variant="text" small @click="asking = false">{{ TT.cancel() }}</Button>
      </div>
    </div>
    <span v-if="note" :class="['td-note', note.tone]" data-testid="status-note"><Icon :name="note.tone === 'warn' ? 'schedule' : 'info'" />{{ note.text }}</span>
    <StillAvailable
      v-if="amb && cur === 'open'"
      :a="{ status: 'approved', available: true, availableUntil: biz.availableUntil, availableAt: biz.availableAt }"
      :busy="busy"
      @renew="renew"
    />
    <ErrorText :text="err" />
  </div>
</template>
