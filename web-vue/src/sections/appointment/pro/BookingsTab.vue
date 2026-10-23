<script setup lang="ts">
// বুকিং: সিরিয়ালের সারি
import { computed, ref } from 'vue';
import { Button, ConfirmDialog, EmptyState, ErrorBox, ErrorText, IconButton, IconLine, PhoneLine, PillTabs, Select, SkeletonCards, Tag, cx, toast } from '@/ui/kit';
import { useMedia } from '@/shell/chrome';
import { call, errorText } from '@/lib/api';
import { formatDate } from '@/lib/format';
import { num, tr } from '@/lib/i18n';
import { useSearchParams } from '@/lib/nav';
import { apptChamber, hhmmText, isActive, statusLabel, statusTone, today, type Appointment } from '../model';
import StatTile from '../StatTile.vue';
import QueuePanel from '../QueuePanel.vue';
import ConfirmApptDialog from './ConfirmApptDialog.vue';
import OnlinePayPro from '../online/OnlinePayPro.vue';

type View = 'today' | 'upcoming' | 'waiting' | 'past';
const props = defineProps<{ list: Appointment[]; loading: boolean; error: string | null; reload: () => void; hasChamber: boolean; onSetup: () => void }>();
const wide = useMedia('(min-width: 1100px)');
const [sp, setSp] = useSearchParams();
const chamber = ref('');
const busyId = ref<string | null>(null);
const err = ref<string | null>(null);
const confirming = ref<Appointment | null>(null);
const cancelling = ref<Appointment | null>(null);
const t0 = today();

const chambers = computed(() => [...new Set(props.list.map((a) => a.chamber).filter(Boolean))]);
const bySerial = (a: Appointment, b: Appointment) => (a.serial || 999) - (b.serial || 999) || a.createdAt - b.createdAt;
const groups = computed<Record<View, Appointment[]>>(() => {
  const inCh = props.list.filter((a) => !chamber.value || a.chamber === chamber.value);
  return {
    today: inCh.filter((a) => a.date === t0 && a.status !== 'expired').sort((a, b) => Number(!isActive(a.status) && a.status !== 'completed') - Number(!isActive(b.status) && b.status !== 'completed') || bySerial(a, b)),
    upcoming: inCh.filter((a) => a.date > t0 && isActive(a.status)).sort((a, b) => a.date - b.date || bySerial(a, b)),
    waiting: inCh.filter((a) => a.status === 'pending').sort((a, b) => a.date - b.date || a.createdAt - b.createdAt),
    past: inCh.filter((a) => a.date < t0 || (a.date > t0 && !isActive(a.status))).sort((a, b) => b.date - a.date || bySerial(a, b)),
  };
});
const view = computed<View>(() => {
  const asked = sp.value.get('v') as View | null;
  const g = groups.value;
  return asked && asked in g ? asked : g.today.length ? 'today' : g.waiting.length ? 'waiting' : 'upcoming';
});
const rows = computed(() => groups.value[view.value]);
const patients = computed(() => new Set(props.list.filter((a) => a.status === 'confirmed' || a.status === 'completed').map((a) => a.otherKey || a.otherName)).size);
const manyCh = computed(() => chambers.value.length > 1 && !chamber.value);

async function act(a: Appointment, op: string, extra: Record<string, unknown> = {}) {
  busyId.value = a.id;
  err.value = null;
  try {
    await call('appointment_update', { id: a.id, op, providerNote: '', ...extra });
    props.reload();
    return true;
  } catch (e) {
    err.value = errorText(e);
    return false;
  } finally {
    busyId.value = null;
  }
}
const name = (a: Appointment) => a.otherName || tr('Deleted account', 'মুছে ফেলা অ্যাকাউন্ট');
const nextSerial = (c: Appointment) => 1 + Math.max(0, ...props.list.filter((x) => x.date === c.date && x.chamber === c.chamber && x.serial > 0).map((x) => x.serial));
async function confirmAppt(serial: number | null, note: string) {
  const c = confirming.value!;
  const ok = await act(c, 'confirm', { providerNote: note, ...(serial ? { serial } : {}) });
  if (ok) confirming.value = null;
  return ok;
}
async function cancelAppt() {
  if (await act(cancelling.value!, 'cancel')) {
    cancelling.value = null;
    toast(tr('Appointment cancelled', 'অ্যাপয়েন্টমেন্ট বাতিল হয়েছে'));
  }
}
const cancelText = (c: Appointment) => `${name(c)} · ${formatDate(c.date)}${c.serial ? ' · ' + tr('Serial #', 'সিরিয়াল নং ') + num(c.serial) : ''}`;
const whenLine = (a: Appointment) => (a.date === t0 ? tr('Today', 'আজ') : formatDate(a.date)) + (a.start ? ', ' + tr('from ', '') + hhmmText(a.start) + tr('', ' থেকে') : '');
</script>

<template>
  <SkeletonCards v-if="loading" :n="4" :h="72" />
  <ErrorBox v-else-if="error" :error="error" :on-retry="reload" />
  <div v-else class="col gap-16">
    <div class="stats">
      <StatTile :value="num(groups.today.filter((a) => isActive(a.status)).length)" :label="tr('Today', 'আজ')" />
      <StatTile :value="num(groups.waiting.length)" :label="tr('Waiting', 'অপেক্ষায়')" :class="groups.waiting.length ? 'appt-stat-hot' : undefined" />
      <StatTile :value="num(groups.upcoming.length)" :label="tr('Upcoming', 'সামনের')" />
      <StatTile :value="num(patients)" :label="tr('Patients', 'রোগী')" />
    </div>
    <div class="row wrap appt-toolbar">
      <PillTabs
        secondary
        :tabs="[
          { key: 'today', label: tr('Today', 'আজ'), count: groups.today.length },
          { key: 'waiting', label: tr('Waiting', 'অপেক্ষায়'), count: groups.waiting.length },
          { key: 'upcoming', label: tr('Upcoming', 'সামনের'), count: groups.upcoming.length },
          { key: 'past', label: tr('Past', 'আগের') },
        ]"
        :value="view"
        @change="(k) => setSp(k === 'today' ? {} : { v: k }, { replace: true })"
      />
      <span class="grow" />
      <div v-if="chambers.length > 1" class="appt-ch-select">
        <Select
          v-model="chamber"
          :aria-label="tr('Chamber', 'চেম্বার')"
          :options="[{ value: '', label: tr('All chambers', 'সব চেম্বার') }, ...chambers.map((c) => ({ value: c, label: apptChamber(list.find((a) => a.chamber === c)!) }))]"
        />
      </div>
      <IconButton icon="refresh" :label="tr('Refresh', 'রিফ্রেশ')" @click="reload()" />
    </div>
    <QueuePanel v-if="view === 'today'" />
    <ErrorText :text="err" />
    <EmptyState v-if="!rows.length" icon="calendar_month" :title="tr('No appointments', 'কোনো অ্যাপয়েন্টমেন্ট নেই')">
      <Button v-if="!hasChamber" variant="tonal" icon="add" @click="onSetup()">{{ tr('Add chamber', 'চেম্বার যোগ করুন') }}</Button>
    </EmptyState>
    <div v-else-if="wide" class="table-wrap">
      <table class="table appt-table">
        <thead>
          <tr>
            <th>{{ tr('Serial', 'সিরিয়াল') }}</th>
            <th>{{ tr('Patient', 'রোগী') }}</th>
            <th>{{ tr('Date', 'তারিখ') }}</th>
            <th>{{ tr('Time', 'সময়') }}</th>
            <th v-if="manyCh">{{ tr('Chamber', 'চেম্বার') }}</th>
            <th>{{ tr('Status', 'অবস্থা') }}</th>
            <th>{{ tr('Phone', 'ফোন') }}</th>
            <th :aria-label="tr('Actions', 'কাজ')" />
          </tr>
        </thead>
        <tbody>
          <tr v-for="a in rows" :key="a.id" :class="cx(!isActive(a.status) && a.status !== 'completed' && 'appt-dim')">
            <td><span v-if="a.serial > 0" class="appt-serial-chip num">{{ num(a.serial) }}</span><span v-else class="muted">—</span></td>
            <td class="appt-td-name">
              <div class="t-title-s">{{ name(a) }}</div>
              <div v-if="a.note" class="t-body-s muted clamp2">{{ a.note }}</div>
              <div v-if="a.providerNote" class="t-body-s appt-note">{{ tr('Note: ', 'নোট: ') + a.providerNote }}</div>
              <OnlinePayPro v-if="a.online" :a="a" :name="a.otherName" @changed="reload()" />
            </td>
            <td class="appt-nowrap">{{ a.date === t0 ? tr('Today', 'আজ') : formatDate(a.date) }}</td>
            <td class="appt-nowrap">{{ a.start ? hhmmText(a.start) : '—' }}</td>
            <td v-if="manyCh">{{ apptChamber(a) }}</td>
            <td><Tag :tone="statusTone(a.status)">{{ statusLabel(a.status) }}</Tag></td>
            <td class="appt-phone-cell"><PhoneLine v-if="a.otherPhone" :phone="a.otherPhone" /><span v-else class="muted">—</span></td>
            <td>
              <div class="row wrap appt-acts">
                <template v-if="a.status === 'pending'">
                  <Button small :busy="busyId === a.id" :disabled="!!busyId" @click="confirming = a">{{ tr('Confirm', 'নিশ্চিত করুন') }}</Button>
                  <Button small variant="text" :disabled="!!busyId" @click="act(a, 'decline')">{{ tr('Decline', 'না করুন') }}</Button>
                </template>
                <Button v-else-if="a.status === 'confirmed'" small variant="danger-text" :disabled="!!busyId" @click="cancelling = a">{{ tr('Cancel appointment', 'অ্যাপয়েন্টমেন্ট বাতিল') }}</Button>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <div v-else class="grid-cards appt-grid">
      <article v-for="a in rows" :key="a.id" :class="cx('card col gap-6', !isActive(a.status) && a.status !== 'completed' && 'appt-dim')">
        <div class="row">
          <span v-if="a.serial > 0" class="appt-serial-chip num">{{ num(a.serial) }}</span>
          <span class="t-title-m grow clamp2">{{ name(a) }}</span>
          <Tag :tone="statusTone(a.status)">{{ statusLabel(a.status) }}</Tag>
        </div>
        <IconLine icon="calendar_month">{{ whenLine(a) }}</IconLine>
        <IconLine v-if="a.chamber" icon="local_hospital">{{ apptChamber(a) + (a.room ? ' · ' + a.room : '') }}</IconLine>
        <p v-if="a.note" class="t-body-m">{{ a.note }}</p>
        <p v-if="a.providerNote" class="t-body-m appt-note">{{ tr('Note: ', 'নোট: ') + a.providerNote }}</p>
        <OnlinePayPro v-if="a.online" :a="a" :name="a.otherName" @changed="reload()" />
        <PhoneLine v-if="a.otherPhone" :phone="a.otherPhone" />
        <div class="row wrap appt-acts">
          <template v-if="a.status === 'pending'">
            <Button small :busy="busyId === a.id" :disabled="!!busyId" @click="confirming = a">{{ tr('Confirm', 'নিশ্চিত করুন') }}</Button>
            <Button small variant="text" :disabled="!!busyId" @click="act(a, 'decline')">{{ tr('Decline', 'না করুন') }}</Button>
          </template>
          <Button v-else-if="a.status === 'confirmed'" small variant="danger-text" :disabled="!!busyId" @click="cancelling = a">{{ tr('Cancel appointment', 'অ্যাপয়েন্টমেন্ট বাতিল') }}</Button>
        </div>
      </article>
    </div>

    <ConfirmApptDialog
      v-if="confirming"
      :a="confirming"
      :next="nextSerial(confirming)"
      :on-confirm="confirmAppt"
      :busy="busyId === confirming.id"
      :error="err"
      @close="confirming = null"
    />
    <ConfirmDialog
      v-if="cancelling"
      :title="tr('Cancel appointment?', 'অ্যাপয়েন্টমেন্ট বাতিল করবেন?')"
      :text="cancelText(cancelling)"
      :confirm="tr('Cancel appointment', 'অ্যাপয়েন্টমেন্ট বাতিল')"
      danger
      :busy="busyId === cancelling.id"
      :error="err"
      @confirm="cancelAppt"
      @close="cancelling = null"
    />
  </div>
</template>
