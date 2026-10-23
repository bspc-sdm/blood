<script setup lang="ts">
// রোগীর তালিকা: onOpen = নাম চাপলে (আগের প্রেসক্রিপশন), onWrite = "লিখুন" (শুধু ডাক্তার)
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { Action, Button, EmptyState, ErrorBox, Icon, IconButton, SkeletonCards, Tag, cx, toast } from '@/ui/kit';
import { useMedia } from '@/shell/chrome';
import { errorText } from '@/lib/api';
import { num, toAsciiDigits, tr } from '@/lib/i18n';
import { formatDate } from '@/lib/format';
import { ageText, dayText, deletedName, forDoctor, genderLabel, targetKey, type Actor, type RxAppt, type RxPatient, type RxTarget } from './model';
import { parseOnline } from '../../appointment/online';
import OnlinePayPro from '../../appointment/online/OnlinePayPro.vue';
import { onlineOn } from '../../appointment/online/flags';
import type { Group, PatientsQuery } from './patients';
import PatientRow from './PatientRow.vue';
import PatientAddDialog from './PatientAddDialog.vue';

const props = defineProps<{
  actor: Actor;
  query: PatientsQuery;
  selectedKey?: string | null;
  onOpen?: (t: RxTarget) => void;
  onWrite?: (t: RxTarget) => void;
  onAdded?: (p: RxPatient) => void;
  /** লেখার পাতায় রোগী বাছাই: নাম চাপলেই লেখা */
  pick?: boolean;
}>();
const wide = useMedia('(min-width: 1100px)');
const q = ref('');
const adding = ref(false);
const search = ref<HTMLInputElement | null>(null);
const doctor = computed(() => props.actor.role === 'doctor');

// "/" চাপলে খোঁজার ঘরে
function onKey(e: KeyboardEvent) {
  const el = e.target as HTMLElement | null;
  if (e.key !== '/' || e.ctrlKey || e.metaKey || e.altKey) return;
  if (el && (el.isContentEditable || ['INPUT', 'TEXTAREA', 'SELECT'].includes(el.tagName))) return;
  if (document.querySelector('.overlay')) return;
  e.preventDefault();
  search.value?.focus();
}
onMounted(() => window.addEventListener('keydown', onKey));
onBeforeUnmount(() => window.removeEventListener('keydown', onKey));

const groups = computed<Group[]>(() => {
  const d = props.query.data;
  if (!d) return [];
  const t = toAsciiDigits(q.value.trim().toLowerCase());
  const hit = (...xs: (string | null | undefined)[]) => !t || xs.some((x) => String(x ?? '').toLowerCase().includes(t));
  const out: Group[] = [];
  const appts = d.appointments.filter((a) => hit(a.name));
  if (appts.length) {
    out.push({
      key: 'a',
      title: tr('Appointments (today & tomorrow)', 'অ্যাপের অ্যাপয়েন্টমেন্ট (আজ–কাল)'),
      rows: appts.map((a) => ({
        key: 'a' + a.id, t: { appointmentId: a.id, name: a.name }, icon: 'event_outline', title: a.name || '-',
        sub: [dayText(a.date), a.chamber].filter(Boolean).join(' · '), tag: a.serial ? tr(`Serial ${a.serial}`, `সিরিয়াল ${num(a.serial)}`) : null,
        appt: a,
      })),
    });
  }
  out.push({
    key: 'p',
    title: tr('Entered patients', 'এন্ট্রি করা রোগী'),
    empty: t ? tr('No one found', 'কাউকে পাওয়া যায়নি') : tr('No patients entered yet', 'এখনো কোনো রোগী এন্ট্রি করা হয়নি'),
    rows: d.patients.filter((p) => hit(p.name, p.phone)).map((p) => ({
      key: 'p' + p.id, t: { rxPatientId: p.id, name: p.name, age: p.age, gender: p.gender }, icon: 'person_outline', title: p.name,
      sub: [ageText(p.age), genderLabel(p.gender), p.phone, p.lastRxAt ? tr('Last: ', 'শেষ: ') + formatDate(p.lastRxAt) : ''].filter(Boolean).join(' · '),
      tag: p.linked ? tr('In app', 'অ্যাপে আছেন') : null,
    })),
  });
  const past = d.appPatients.filter((a) => hit(a.name));
  if (past.length) {
    out.push({
      key: 'h',
      title: tr('Came through the app before', 'অ্যাপে আগে এসেছেন'),
      rows: past.map((a) => ({
        key: 'h' + a.appointmentId, t: { appointmentId: a.appointmentId, name: a.name }, icon: 'history', title: a.name || deletedName(),
        sub: tr(`${a.count} visit(s) · last `, `${num(a.count)} বার · শেষ `) + formatDate(a.last), tag: null,
      })),
    });
  }
  return out;
});

const click = computed(() => (props.pick ? props.onWrite : props.onOpen));
const total = computed(() => {
  const d = props.query.data;
  return d ? d.appointments.length + d.patients.length + d.appPatients.length : 0;
});
const rowOpen = computed(() => (props.pick ? undefined : props.onOpen));
function added(p: RxPatient, existed: boolean) {
  adding.value = false;
  toast(existed ? tr('This patient was already entered', 'এই রোগী আগে থেকেই ছিলেন') : tr('Patient added', 'রোগী যোগ হয়েছে'));
  props.query.refetch();
  props.onAdded?.(p);
}
// অনলাইন অ্যাপয়েন্টমেন্ট: ফির অবস্থা/বোতাম আর কল (সহকারী হলে doctorUid সাথে)
const onlineOf = (a: RxAppt | undefined) => (a?.online && onlineOn() ? { ...a, ...parseOnline(a), start: typeof a.start === 'string' ? a.start : '' } : null);
const extra = computed(() => forDoctor(props.actor));
function rowKey(e: KeyboardEvent, t: RxTarget) {
  if (click.value && e.key === 'Enter' && e.target === e.currentTarget) click.value(t);
}
</script>

<template>
  <div class="col gap-12">
    <div class="row wrap rxd-toolbar">
      <label class="rxd-search">
        <Icon name="search" />
        <input
          ref="search"
          :value="q"
          :placeholder="tr('Search name or phone', 'নাম বা ফোন দিয়ে খুঁজুন')"
          :aria-label="tr('Search name or phone', 'নাম বা ফোন দিয়ে খুঁজুন')"
          @input="q = ($event.target as HTMLInputElement).value.slice(0, 40)"
          @keydown.esc="q = ''"
        />
        <kbd v-if="wide" class="rxd-kbd" aria-hidden="true">/</kbd>
      </label>
      <span class="grow" />
      <IconButton icon="refresh" :label="tr('Refresh', 'রিফ্রেশ')" @click="query.refetch()" />
      <Button icon="add" @click="adding = true">{{ doctor ? tr('Add patient', 'রোগী যোগ') : tr('New patient', 'নতুন রোগী') }}</Button>
    </div>

    <SkeletonCards v-if="query.isPending" :n="4" :h="64" />
    <ErrorBox v-else-if="query.isError" :error="errorText(query.error)" :on-retry="() => query.refetch()" />
    <EmptyState
      v-else-if="!total"
      icon="person_outline"
      :title="tr('No patients yet', 'এখনো কোনো রোগী নেই')"
      :text="tr('Appointments from the app and patients you enter appear here.', 'অ্যাপের অ্যাপয়েন্টমেন্ট আর এন্ট্রি করা রোগী এখানে আসবে।')"
    />
    <div v-else-if="wide" class="table-wrap">
      <table class="table rxd-table">
        <thead>
          <tr>
            <th>{{ tr('Patient', 'রোগী') }}</th>
            <th>{{ tr('Details', 'বিস্তারিত') }}</th>
            <th :aria-label="tr('Actions', 'কাজ')" />
          </tr>
        </thead>
        <tbody v-for="g in groups" :key="g.key">
          <tr class="rxd-group">
            <th colspan="3" scope="colgroup">{{ g.title }} <span class="rxd-count num">{{ num(g.rows.length) }}</span></th>
          </tr>
          <tr v-if="!g.rows.length"><td colspan="3" class="muted">{{ g.empty }}</td></tr>
          <template v-else>
          <tr
            v-for="r in g.rows"
            :key="r.key"
            :class="cx(!!click && 'rxd-click', selectedKey === targetKey(r.t) && 'rxd-sel')"
            :tabindex="click ? 0 : undefined"
            :aria-selected="click ? selectedKey === targetKey(r.t) : undefined"
            @click="click?.(r.t)"
            @keydown="rowKey($event, r.t)"
          >
            <td class="rxd-td-name">
              <span class="row gap-12">
                <span class="rxd-row-ic"><Icon :name="r.icon" /></span>
                <span class="t-title-s">{{ r.title }}</span>
              </span>
            </td>
            <td>
              <span class="row wrap gap-6">
                <span class="t-body-m muted">{{ r.sub }}</span>
                <Tag v-if="r.tag" tone="info">{{ r.tag }}</Tag>
              </span>
              <OnlinePayPro v-if="onlineOf(r.appt)" :a="onlineOf(r.appt)!" :name="r.title" :extra="extra" @changed="query.refetch()" />
            </td>
            <td class="rxd-td-acts">
              <span v-if="onWrite || rowOpen" class="row rxd-acts">
                <Action v-if="onWrite" icon="edit_note" primary @click.stop="onWrite(r.t)">{{ tr('Write', 'লিখুন') }}</Action>
                <Button v-if="rowOpen" small variant="text" icon="history" data-testid="rx-history" @click.stop="rowOpen(r.t)">{{ tr('Earlier prescriptions', 'আগের প্রেসক্রিপশন') }}</Button>
              </span>
            </td>
          </tr>
          </template>
        </tbody>
      </table>
    </div>
    <template v-else>
      <section v-for="g in groups" :key="g.key" class="col gap-6">
        <h2 class="rxd-group-title">{{ g.title }}</h2>
        <p v-if="!g.rows.length" class="t-body-m muted rxd-pad">{{ g.empty }}</p>
        <PatientRow
          v-for="r in g.rows"
          :key="r.key"
          :r="r"
          :selected="selectedKey === targetKey(r.t)"
          :on-click="click ? () => click!(r.t) : undefined"
          :on-write="onWrite && !pick ? () => onWrite!(r.t) : undefined"
        >
          <OnlinePayPro v-if="onlineOf(r.appt)" :a="onlineOf(r.appt)!" :name="r.title" :extra="extra" @changed="query.refetch()" />
        </PatientRow>
      </section>
    </template>

    <PatientAddDialog v-if="adding" :actor="actor" @close="adding = false" @added="added" />
  </div>
</template>
