<script setup lang="ts">
// ডাক্তার/সহকারী: একটা চেম্বারের আজকের সারি চালানো
import { computed, ref } from 'vue';
import { Action, Button, ErrorText, Tag, cx, toast } from '@/ui/kit';
import { call, errorText } from '@/lib/api';
import { isBangla, num, tr } from '@/lib/i18n';
import { lateText, type QueueChamber, type QueueRow } from './queue';

const props = defineProps<{ c: QueueChamber; many: boolean; doctorUid: string | null; reload: () => void }>();
const busy = ref<string | null>(null);
const err = ref<string | null>(null);
const qs = computed(() => props.c.queue);
async function run(key: string, action: string, b: Record<string, unknown>, done?: string) {
  busy.value = key;
  err.value = null;
  try {
    const extra = props.doctorUid ? { doctorUid: props.doctorUid } : {};
    const r = await call<{ stale?: boolean }>(action, { ...extra, ...b });
    // stale: অন্য কেউ (ডাক্তার/সহকারী) এইমাত্র বদলেছেন — কিছু হয়নি, শুধু নতুন অবস্থা দেখানো
    if (done && !r?.stale) toast(done);
    props.reload();
  } catch (e) {
    err.value = errorText(e);
  } finally {
    busy.value = null;
  }
}
// expect = এখন যে নম্বর দেখছি (০ = কেউ না); date = এই সারির দিন
const control = (op: string, more: Record<string, unknown> = {}, done?: string) =>
  run(op + (more.appointmentId ?? ''), 'queue_control', {
    chamberKey: props.c.chamberKey, op, expect: qs.value.started ? qs.value.current || 0 : 0, ...(props.c.date ? { date: props.c.date } : {}), ...more,
  }, done);
const status = computed(() => (qs.value.paused ? tr('Break', 'বিরতি') : qs.value.finished ? tr('Done', 'শেষ') : qs.value.started ? tr('Running', 'চলছে') : tr('Not started', 'শুরু হয়নি')));
const rows = computed(() =>
  props.c.rows
    .filter((r) => r.state !== 'cancelled')
    .map((r: QueueRow) => ({
      r,
      now: qs.value.started && qs.value.current === r.serial,
      asked: r.late?.status === 'asked' && (r.state === 'waiting' || r.state === 'later'),
    })),
);
</script>

<template>
  <section class="card col gap-12 appt-qp" :aria-label="tr('Today\'s serial', 'আজকের সিরিয়াল')">
    <div class="row wrap gap-6">
      <span class="col gap-2 grow">
        <span class="t-title-m">{{ tr("Today's serial", 'আজকের সিরিয়াল') }}</span>
        <span v-if="many" class="t-body-s muted">{{ isBangla() && c.nameBn ? c.nameBn : c.name }}</span>
      </span>
      <Tag :tone="qs.paused ? 'primary' : qs.started && !qs.finished ? 'good' : undefined">{{ status }}</Tag>
    </div>
    <div class="appt-q-nums">
      <div class="col gap-2">
        <span class="t-body-s muted">{{ tr('Seeing now', 'এখন দেখছি') }}</span>
        <span class="appt-q-big num">{{ qs.started && qs.current ? num(qs.current) : '—' }}</span>
      </div>
      <div class="col gap-2">
        <span class="t-body-s muted">{{ tr('Next', 'পরের') }}</span>
        <span class="appt-q-big soft num">{{ qs.next ? num(qs.next) : '—' }}</span>
      </div>
      <div class="col gap-2">
        <span class="t-body-s muted">{{ tr('Avg.', 'গড়') }}</span>
        <span class="appt-q-big soft num">{{ tr(`${c.avgMin}m`, `${num(c.avgMin)} মি.`) }}</span>
      </div>
    </div>
    <div class="row wrap gap-6">
      <Button v-if="!qs.started" icon="chevron_right" :busy="busy === 'start'" :disabled="!!busy" @click="control('start')">{{ tr('Start', 'শুরু করুন') }}</Button>
      <template v-else>
        <Button icon="chevron_right" :busy="busy === 'next'" :disabled="!!busy || (!qs.next && qs.finished)" @click="control('next')">{{ tr('Next patient', 'পরের রোগী') }}</Button>
        <Button v-if="qs.paused" variant="tonal" icon="sync" :busy="busy === 'resume'" :disabled="!!busy" @click="control('resume')">{{ tr('Resume', 'আবার শুরু') }}</Button>
        <Button v-else variant="outline" icon="schedule" :busy="busy === 'pause'" :disabled="!!busy || qs.finished" @click="control('pause')">{{ tr('Break', 'বিরতি') }}</Button>
      </template>
    </div>
    <ErrorText :text="err" />
    <div class="appt-qp-rows">
      <div v-for="{ r, now, asked } in rows" :key="r.id" :class="cx('appt-qp-row', now && 'now', (r.state === 'done' || r.state === 'passed') && 'appt-dim')">
        <button
          type="button"
          class="appt-qp-pick"
          :disabled="!!busy || now"
          :title="tr('Seeing now', 'এখন দেখছি')"
          @click="control('set', { appointmentId: r.id }, tr(`Seeing now: #${r.serial}`, `এখন দেখছি: ${num(r.serial)}`))"
        >
          <span class="appt-serial-chip num">{{ num(r.serial) }}</span>
          <span class="col gap-2 grow appt-qp-name">
            <span class="t-title-s ellipsis">{{ r.name || tr('Deleted account', 'মুছে ফেলা অ্যাকাউন্ট') }}</span>
            <span class="row wrap gap-6">
              <Tag v-if="now" tone="good">{{ tr('Seeing now', 'এখন দেখছি') }}</Tag>
              <Tag v-if="r.state === 'done'">{{ tr('Seen', 'দেখা হয়েছে') }}</Tag>
              <Tag v-if="r.state === 'later'">{{ tr('At the end', 'শেষে') }}</Tag>
              <Tag v-if="r.late && (asked || r.state === 'later' || r.late.status === 'ok') && r.state !== 'done'" :tone="asked ? 'error' : 'info'">{{ lateText(r.late.min) }}</Tag>
            </span>
            <span v-if="asked && r.late?.note" class="t-body-s muted">{{ r.late.note }}</span>
          </span>
        </button>
        <span v-if="asked" class="row gap-6 appt-qp-reply">
          <Action primary :busy="busy === 'ok' + r.id" :disabled="!!busy" @click="run('ok' + r.id, 'appointment_late_reply', { id: r.id, op: 'ok' })">{{ tr('OK', 'ঠিক আছে') }}</Action>
          <Action :busy="busy === 'later' + r.id" :disabled="!!busy" @click="run('later' + r.id, 'appointment_late_reply', { id: r.id, op: 'later' })">{{ tr('Come later', 'পরে আসুন') }}</Action>
        </span>
      </div>
    </div>
  </section>
</template>
