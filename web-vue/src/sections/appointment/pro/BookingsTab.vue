<script setup lang="ts">
// বুকিং: সিরিয়ালের সারি
import { computed, ref } from 'vue';
import { Button, ConfirmDialog, Dialog, EmptyState, ErrorBox, ErrorText, IconButton, IconLine, Select, SkeletonCards, Tag, TextInput, cx, toast } from '@/ui/kit';
import { useMedia } from '@/shell/chrome';
import { ServerError, call, errorText } from '@/lib/api';
import { formatDate } from '@/lib/format';
import { num, tr } from '@/lib/i18n';
import { useSearchParams } from '@/lib/nav';
import { apptChamber, hhmmText, isActive, statusLabel, statusTone, today, type Appointment } from '../model';
import StatTile from '../StatTile.vue';
import QueueSegs, { type QSeg } from '@/ui/listing/QueueSegs.vue';
import QueuePanel from '../QueuePanel.vue';
import ConfirmApptDialog from './ConfirmApptDialog.vue';
import OnlinePayPro from '../online/OnlinePayPro.vue';
import { onlineOn } from '../online/flags';
import { T as OT } from '../online';
import ContactActions from '@/calls/ContactActions.vue';
import { chatOn, openChatAt } from '@/chat/open';
import { apptCallOpen } from '@/calls/window';
import { payOn } from '../../pay/features';
import { linkDue } from '../../pay/model';
import { T as PT } from '../../pay/text';
import { useNow } from '@/ui/composables';

type View = 'today' | 'upcoming' | 'waiting' | 'past';
// ভাগ (সেবার "আমার কাজ"-এর এক ছাঁচ, QueueSegs): নতুন = অপেক্ষায় (নিশ্চিত করা বাকি), চলছে = আজ আর সামনের নিশ্চিত, শেষ = আগের/শেষ/বাতিল
const props = defineProps<{ list: Appointment[]; loading: boolean; error: string | null; reload: () => void; hasChamber: boolean; onSetup: () => void }>();
/** চেম্বারের অগ্রিম বাকি: তালিকার payRequestId/payStatus (open/rejected) থেকেই, আলাদা খোঁজ নয় */
const advanceDue = (a: Appointment) => !a.online && isActive(a.status) && payOn('pay_chamber_advance') && linkDue(a.payLink);
const [sp, setSp] = useSearchParams();
const chamber = ref('');
const busyId = ref<string | null>(null);
const err = ref<string | null>(null);
const confirming = ref<Appointment | null>(null);
const cancelling = ref<Appointment | null>(null);
const t0 = today();
const now = useNow(30_000);

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
const segs = computed<Record<QSeg, Appointment[]>>(() => {
  const g = groups.value;
  const active = [...g.today.filter((a) => a.status === 'confirmed'), ...g.upcoming.filter((a) => a.status === 'confirmed')];
  return { new: g.waiting, active, done: [...g.today.filter((a) => !isActive(a.status)), ...g.past] };
});
const view = computed<QSeg>(() => {
  const asked = sp.value.get('v');
  // পুরোনো লিংক (?v=today|waiting|upcoming|past)
  const k = asked === 'waiting' ? 'new' : asked === 'today' || asked === 'upcoming' ? 'active' : asked === 'past' ? 'done' : asked;
  // না বাছলে: আজ নিশ্চিত কেউ থাকলে "চলছে" (আজকের কাজ), নইলে অপেক্ষায় কেউ থাকলে "নতুন"
  if (k === 'new' || k === 'active' || k === 'done') return k;
  return groups.value.today.some((a) => a.status === 'confirmed') ? 'active' : segs.value.new.length ? 'new' : 'active';
});
const rows = computed(() => segs.value[view.value]);
// ডেস্কটপে (৯০০px+) তালিকা-বিস্তারিত: বাঁয়ে ছোট সারি, ডানে বাছা সিরিয়াল (না বাছলে প্রথমটা) — কাজের বোতাম সেখানে
const md = useMedia('(min-width: 900px)');
const picked = ref<string | null>(null);
const sel = computed(() => rows.value.find((a) => a.id === picked.value) ?? rows.value[0] ?? null);
const shownCards = computed(() => (md.value ? (sel.value ? [sel.value] : []) : rows.value));
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
    // R8: ফি দেওয়া (claimed/paid) অনলাইন অ্যাপয়েন্টমেন্ট বাতিল/না করলে কারণ লাগে
    if (e instanceof ServerError && e.code === 'reason_required' && (op === 'cancel' || op === 'decline')) {
      cancelling.value = null;
      askReason(a, op);
      return false;
    }
    err.value = errorText(e);
    return false;
  } finally {
    busyId.value = null;
  }
}
// অনলাইন, রোগী ফি পাঠিয়েছেন/পরিশোধিত: বাতিল বা "না করুন"-এ কারণ চাই (সার্ভারের reason_required)
const needsReason = (a: Appointment) => a.online && (a.payState === 'claimed' || a.payState === 'paid');
const reasonFor = ref<{ a: Appointment; op: 'cancel' | 'decline' } | null>(null);
const reason = ref('');
function askReason(a: Appointment, op: 'cancel' | 'decline') {
  err.value = null;
  reason.value = '';
  reasonFor.value = { a, op };
}
function decline(a: Appointment) {
  if (needsReason(a)) askReason(a, 'decline');
  else act(a, 'decline');
}
function cancel(a: Appointment) {
  if (needsReason(a)) askReason(a, 'cancel');
  else cancelling.value = a;
}
async function sendReason() {
  const r = reasonFor.value;
  if (!r) return;
  if (!reason.value.trim()) {
    err.value = OT.reasonReq();
    return;
  }
  if (await act(r.a, r.op, { providerNote: reason.value.trim() })) {
    reasonFor.value = null;
    if (r.op === 'cancel') toast(tr('Appointment cancelled', 'অ্যাপয়েন্টমেন্ট বাতিল হয়েছে'));
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
      <QueueSegs
        :label="tr('Bookings', 'বুকিং')"
        :value="view"
        :counts="{ new: segs.new.length, active: segs.active.length, done: segs.done.length }"
        @change="(k) => setSp({ v: k }, { replace: true })"
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
    <QueuePanel v-if="view === 'active'" />
    <ErrorText :text="err" />
    <EmptyState v-if="!rows.length" icon="calendar_month" :title="tr('No appointments', 'কোনো অ্যাপয়েন্টমেন্ট নেই')">
      <Button v-if="!hasChamber" variant="tonal" icon="add" @click="onSetup()">{{ tr('Add chamber', 'চেম্বার যোগ করুন') }}</Button>
    </EmptyState>
    <div v-else :class="md ? 'q-md' : 'grid-cards appt-grid'">
      <ul v-if="md" class="q-list" :aria-label="tr('Bookings', 'বুকিং')">
        <li v-for="a in rows" :key="a.id">
          <button type="button" :class="cx('q-item', !isActive(a.status) && a.status !== 'completed' && 'appt-dim')" :aria-current="a.id === sel?.id" data-testid="q-item" @click="picked = a.id">
            <span v-if="a.serial > 0" class="appt-serial-chip num">{{ num(a.serial) }}</span>
            <span class="col gap-2 grow" :style="{ minWidth: 0 }">
              <span class="t-title-s">{{ name(a) }}</span>
              <span class="t-body-s muted">{{ whenLine(a) + (manyCh && a.chamber ? ' · ' + apptChamber(a) : '') }}</span>
            </span>
            <Tag :tone="statusTone(a.status)">{{ statusLabel(a.status) }}</Tag>
          </button>
        </li>
      </ul>
      <article v-for="a in shownCards" :key="a.id" :class="cx('card col gap-6', md && 'q-detail', !isActive(a.status) && a.status !== 'completed' && 'appt-dim')">
        <div class="row">
          <span v-if="a.serial > 0" class="appt-serial-chip num">{{ num(a.serial) }}</span>
          <span class="t-title-m grow clamp2">{{ name(a) }}</span>
          <Tag :tone="statusTone(a.status)">{{ statusLabel(a.status) }}</Tag>
        </div>
        <!-- চেম্বারের অগ্রিম বাকি (তালিকার payRequestId/payStatus; PAYMENTS.md) — অনলাইনের ফি নিচের OnlinePayPro-তে -->
        <div v-if="advanceDue(a)" class="row"><Tag tone="error" data-testid="advance-due">{{ PT.advanceDue() }}</Tag></div>
        <IconLine icon="calendar_month">{{ whenLine(a) }}</IconLine>
        <IconLine v-if="a.chamber" icon="local_hospital">{{ apptChamber(a) + (a.room ? ' · ' + a.room : '') }}</IconLine>
        <p v-if="a.note" class="t-body-m">{{ a.note }}</p>
        <p v-if="a.providerNote" class="t-body-m appt-note">{{ tr('Note: ', 'নোট: ') + a.providerNote }}</p>
        <OnlinePayPro v-if="a.online && onlineOn()" :a="a" :name="a.otherName" @changed="reload()" />
        <ContactActions :message="chatOn()" @message="openChatAt" ctx="appointment" :ref-id="a.id" :peer-name="name(a)" :phone="a.otherPhone || null" :show="apptCallOpen(a, now)" show-number />
        <div class="row wrap appt-acts">
          <template v-if="a.status === 'pending'">
            <Button small :busy="busyId === a.id" :disabled="!!busyId" @click="confirming = a">{{ tr('Confirm', 'নিশ্চিত করুন') }}</Button>
            <Button small variant="text" :disabled="!!busyId" @click="decline(a)">{{ tr('Decline', 'না করুন') }}</Button>
          </template>
          <Button v-else-if="a.status === 'confirmed'" small variant="danger-text" :disabled="!!busyId" @click="cancel(a)">{{ tr('Cancel appointment', 'অ্যাপয়েন্টমেন্ট বাতিল') }}</Button>
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
    <Dialog v-if="reasonFor" size="small" :title="reasonFor.op === 'cancel' ? tr('Cancel appointment?', 'অ্যাপয়েন্টমেন্ট বাতিল করবেন?') : tr('Decline', 'না করুন')" @close="() => !busyId && (reasonFor = null)">
      <p class="t-body-m muted">{{ cancelText(reasonFor.a) }}</p>
      <p class="t-body-m" data-testid="reason-needed">{{ OT.reasonNeeded() }}</p>
      <TextInput :label="OT.reasonReq()" data-testid="cancel-reason" :model-value="reason" @update:model-value="(v) => (reason = v.slice(0, 200))" />
      <ErrorText :text="err" />
      <template #actions>
        <Button variant="text" :disabled="!!busyId" @click="reasonFor = null">{{ tr('Cancel', 'বাতিল') }}</Button>
        <Button variant="danger" :busy="busyId === reasonFor.a.id" data-testid="cancel-reason-send" @click="sendReason">{{ reasonFor.op === 'cancel' ? tr('Cancel appointment', 'অ্যাপয়েন্টমেন্ট বাতিল') : tr('Decline', 'না করুন') }}</Button>
      </template>
    </Dialog>
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
