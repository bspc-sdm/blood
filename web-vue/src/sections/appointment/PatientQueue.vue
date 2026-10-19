<script setup lang="ts">
// বিস্তারিতে পুরো সিরিয়ালের কার্ড + "দেরি হবে"
import { computed, ref } from 'vue';
import { Button, Icon, Tag, cx } from '@/ui/kit';
import { formatTime } from '@/lib/format';
import { num, tr } from '@/lib/i18n';
import type { Appointment } from './model';
import { arriveText, queueLive, stateText, usePatientQueue } from './queue';
import LateDialog from './LateDialog.vue';

const props = defineProps<{ a: Appointment }>();
const query = usePatientQueue(() => props.a);
const q = computed(() => (queueLive(props.a) && query.data?.live ? query.data : null));
const asking = ref(false);
const lateReply = (st: string) => (st === 'ok' ? tr('Doctor: OK', 'ডাক্তার: ঠিক আছে') : st === 'later' ? tr('Doctor: come later', 'ডাক্তার: পরে আসুন') : tr('Waiting for reply', 'উত্তরের অপেক্ষায়'));
function sent() {
  asking.value = false;
  query.refetch();
}
</script>

<template>
  <section v-if="q" class="card col gap-8 appt-q" aria-live="polite">
    <div class="row wrap gap-6">
      <span class="t-label-m grow">{{ tr('Serial today', 'আজকের সিরিয়াল') }}</span>
      <Tag v-if="q.paused" tone="primary">{{ tr('Doctor is on a short break', 'ডাক্তার একটু বিরতিতে') }}</Tag>
    </div>
    <div class="appt-q-nums">
      <div class="col gap-2">
        <span class="t-body-s muted">{{ tr('Now', 'এখন চলছে') }}</span>
        <span class="appt-q-big num">{{ q.started && q.current ? num(q.current) : '—' }}</span>
      </div>
      <div class="col gap-2">
        <span class="t-body-s muted">{{ tr('Your serial', 'আপনার সিরিয়াল') }}</span>
        <span class="appt-q-big mine num">{{ num(q.serial ?? a.serial) }}</span>
      </div>
    </div>
    <span v-if="stateText(q)" :class="cx('t-title-s', q.state === 'now' && 'c-primary')">{{ stateText(q) }}</span>
    <template v-else>
      <span v-if="!q.started" class="t-body-m muted">{{ tr('Not started yet', 'এখনো শুরু হয়নি') }}</span>
      <span class="t-title-s">{{ tr(`${q.ahead} before you`, `আপনার আগে ${num(q.ahead ?? 0)} জন`) }}</span>
      <span v-if="q.later" class="t-body-m appt-note">{{ tr('You will be seen at the end', 'আপনাকে সবার শেষে দেখা হবে') }}</span>
      <span class="t-body-m muted">{{ tr(`Doctor takes ~${q.avgMin} min each`, `ডাক্তার গড়ে ${num(q.avgMin ?? 10)} মিনিট নেন`) }}</span>
      <div v-if="q.eta" class="appt-q-eta">
        <Icon name="schedule" />
        <span>{{ tr(`Estimated ${formatTime(q.eta)}`, `আনুমানিক সময় ${formatTime(q.eta)}`) }}{{ arriveText(q) ? ' — ' + arriveText(q) : '' }}</span>
      </div>
    </template>
    <span v-if="q.late" :class="cx('t-body-m', q.late.status === 'asked' ? 'muted' : 'appt-note')">{{ tr(`Asked for ${q.late.min} min`, `সময় চেয়েছেন: ${num(q.late.min)} মিনিট`) }} · {{ lateReply(q.late.status) }}</span>
    <div v-if="q.canLate" class="row">
      <Button variant="outline" small icon="directions_run" @click="asking = true">{{ tr('I will be late', 'দেরি হবে') }}</Button>
    </div>
    <LateDialog v-if="asking" :a="a" :current="q.late" @close="asking = false" @sent="sent" />
  </section>
</template>
