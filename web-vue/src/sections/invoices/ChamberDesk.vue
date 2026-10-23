<script setup lang="ts">
// চেম্বারের ডেস্ক (ডাক্তার বা সহকারী, /work/rx/desk): আজকের সিরিয়াল (queue_today), "এসেছেন" (চেক-ইন),
// সরাসরি আসা রোগী যোগ, ফি নেওয়া (দেখানোর ধরন অনুযায়ী) → রসিদ, আর আজকের আদায়ের তালিকা (invoice_list_biz, chamber)।
// ডেস্কটপে (OfflineStore): আজকের তালিকা স্থানীয় (chamberDay), চেক-ইন আর ফি অফলাইনে লেখা হয়ে পরে মেলে।
import { computed, reactive, ref, shallowRef, watch } from 'vue';
import { Button, EmptyState, ErrorText, Icon, SkeletonCards, Tag, cx, toast } from '@/ui/kit';
import { call, errorText } from '@/lib/api';
import { useCall, useQ } from '@/lib/query';
import { isBangla, num, toAsciiDigits, tr } from '@/lib/i18n';
import { useSession } from '@/lib/session';
import { REFRESH, type QueueChamber } from '@/sections/appointment/queue';
import { T } from './text';
import { money, parsePage, todayStr, type Invoice } from './model';
import { errText, issue } from './actions';
import { useOffline } from './offline';
import OfflineBar from './OfflineBar.vue';
import FeeDialog, { type DeskChamber } from './FeeDialog.vue';
import ReceiptDialog from './ReceiptDialog.vue';
import BizInvoiceList from './BizInvoiceList.vue';

interface DeskRow { id: string; serial: number; name: string; state: string; checkedIn: boolean; chamberKey: string; feeNo: string }
interface DeskDay { chambers: (DeskChamber & { rows: DeskRow[] })[] }
const props = defineProps<{ doctorUid: string | null }>();
const s = useSession();
const bizId = computed(() => props.doctorUid ?? s.uid ?? '');
const off = useOffline('chamber', bizId);

const rowsOf = (c: any): DeskRow[] =>
  (c.rows ?? c.appointments ?? []).map((r: any) => ({
    id: String(r.id), serial: Number(r.serial) || 0, name: String(r.name ?? r.patientName ?? ''), state: String(r.state ?? r.status ?? ''),
    checkedIn: r.checkedIn === true || !!r.arrivedAt || r.status === 'checkedin' || r.status === 'arrived', chamberKey: String(c.chamberKey ?? r.chamberKey ?? ''),
    feeNo: String(r.feeNo ?? ''),
  }));
/** ডেস্কটপের chamberDay ({appointments, fees}) → চেম্বার অনুযায়ী ভাগ (একই আকার queue_today-র মতো) */
function fromDesktop(raw: any) {
  const by = new Map<string, any[]>();
  for (const a of raw?.appointments ?? []) by.set(a.chamberKey, [...(by.get(a.chamberKey) ?? []), a]);
  const info = new Map<string, any>((raw?.chambers ?? []).map((c: any) => [c.chamberKey, c]));
  for (const k of info.keys()) if (!by.has(k)) by.set(k, []);
  return {
    chambers: [...by.entries()].map(([chamberKey, rows]) => {
      const c = info.get(chamberKey);
      return { chamberKey, name: c?.name ?? chamberKey, nameBn: c?.nameBn, fees: c?.fees ?? raw.fees ?? null, rows: rows.sort((x, y) => (x.serial ?? 999) - (y.serial ?? 999)) };
    }),
  };
}
const dayOf = (raw0: any): DeskDay => {
  const raw = Array.isArray(raw0?.appointments) ? fromDesktop(raw0) : raw0;
  return {
  chambers: (raw?.chambers ?? []).map((c: any) => ({
    chamberKey: String(c.chamberKey ?? c.key ?? ''),
    name: isBangla() && c.nameBn ? c.nameBn : String(c.name ?? ''),
    fees: c.fees ?? null,
    rows: rowsOf(c).filter((r) => r.state !== 'cancelled'),
  })),
  };
};
// আজকের সারি: ওয়েবে queue_today (সহকারী হলে doctorUid), ডেস্কটপে স্থানীয়
const q = useCall<{ chambers: QueueChamber[] }>('queue_today', () => (props.doctorUid ? { doctorUid: props.doctorUid } : {}), () => ({ enabled: !off.st.desktop, refetchInterval: REFRESH, staleTime: 10_000 }));
const local = shallowRef<any>(null);
async function loadLocal() {
  const st = off.store();
  if (st) local.value = await st.chamberDay(todayStr());
}
watch(() => off.st.ready, (r) => r && loadLocal(), { immediate: true });
const day = computed<DeskDay | null>(() => (off.st.desktop ? (local.value ? dayOf(local.value) : null) : q.data ? dayOf(q.data) : null));
const chambers = computed(() => day.value?.chambers ?? []);
// ফি নেওয়ার চেম্বার (সরাসরি আসা রোগীসহ): assistant_chambers — ডাক্তারের সব চেম্বার আর ফি ("৭০০ টাকা" → নতুন রোগীর ফি ৭০০)
const ac = useCall<{ chambers?: { chamberKey: string; name: string; nameBn?: string; fee?: string; noFee?: boolean }[] }>(
  'assistant_chambers',
  () => (props.doctorUid ? { doctorUid: props.doctorUid } : {}),
  () => ({ enabled: !off.st.desktop, staleTime: 5 * 60_000 }),
);
const feeNum = (f?: string) => {
  const m = toAsciiDigits(f ?? '').replace(/,/g, '').match(/\d+(\.\d+)?/);
  return m ? Number(m[0]) : null;
};
const feeChambers = computed<DeskChamber[]>(() => {
  const list: DeskChamber[] = (ac.data?.chambers ?? []).map((c) => {
    const n = c.noFee ? 0 : feeNum(c.fee);
    return { chamberKey: c.chamberKey, name: isBangla() && c.nameBn ? c.nameBn : c.name, fees: n != null ? { new: n } : null };
  });
  // আজকের সারির চেম্বার (ডেস্কটপ বা পুরোনো সার্ভারে assistant_chambers নেই) — আগে নেই এমনগুলো যোগ
  for (const c of chambers.value) if (!list.some((x) => x.chamberKey === c.chamberKey)) list.push({ chamberKey: c.chamberKey, name: c.name, fees: c.fees ?? null });
  return list.map((c) => ({ ...c, fees: { ...(chambers.value.find((x) => x.chamberKey === c.chamberKey)?.fees ?? {}), ...(c.fees ?? {}) } }));
});

// আজকের রসিদ (ফি নেওয়া হয়েছে কি না দেখাতে) — তালিকার প্রথম পাতা
const todays = useQ(() => ({
  queryKey: ['invoice_list_biz', 'chamber', bizId.value, 'today', 'desk'],
  queryFn: () => call('invoice_list_biz', { kind: 'chamber', bizId: bizId.value, period: 'today' }).then(parsePage),
  enabled: !!bizId.value && !off.st.desktop,
  refetchInterval: REFRESH,
}));
const localPaid = reactive(new Map<string, Invoice>());
const localIn = reactive(new Set<string>());
const paidFor = computed(() => {
  const m = new Map<string, Invoice>();
  for (const i of todays.data?.items ?? []) if (i.refType === 'appointment' && i.refId && i.status !== 'void') m.set(i.refId, i);
  for (const [k, v] of localPaid) m.set(k, v);
  return m;
});

const busy = ref<string | null>(null);
const err = ref<string | null>(null);
async function checkIn(r: DeskRow) {
  busy.value = r.id;
  err.value = null;
  try {
    // ওয়েবে chamber_checkin; ডেস্কটপে অফলাইন "checkin"
    await issue('chamber_checkin', { appointmentId: r.id, ...(props.doctorUid ? { doctorUid: props.doctorUid } : {}) }, () => ({ type: 'checkin', data: { appointmentId: r.id } }));
    localIn.add(r.id);
    toast(T.checkedIn() + ': ' + r.name);
    if (!off.st.desktop) q.refetch();
  } catch (e) {
    err.value = errText(e);
  } finally {
    busy.value = null;
  }
}
const feeFor = ref<DeskRow | 'walkin' | null>(null);
const receipt = ref<{ inv: Invoice; print: boolean } | null>(null);
function feeDone(inv: Invoice | null) {
  const f = feeFor.value;
  if (f && f !== 'walkin' && inv) localPaid.set(f.id, inv);
  if (f && f !== 'walkin') localIn.add(f.id);
  feeFor.value = null;
  if (inv) receipt.value = { inv, print: true };
  if (off.st.desktop) loadLocal();
}
const total = computed(() => chambers.value.reduce((a, c) => a + c.rows.length, 0));
const ASSIST_VOID_MS = 2 * 3600 * 1000;
const canVoidInv = (i: Invoice) => !props.doctorUid || (i.issuedBy === s.uid && Date.now() - i.issuedAt < ASSIST_VOID_MS);
</script>

<template>
  <div class="col gap-16 inv-desk" data-testid="chamber-desk">
    <OfflineBar :st="off.st" :sync="off.syncNow" />
    <section class="card rxp-panel col gap-12">
      <div class="rxp-panel-head">
        <span class="rxp-panel-ic"><Icon name="event_outline" /></span>
        <h2 class="t-title-m grow">{{ T.deskTitle() }}</h2>
        <span v-if="total" class="rxp-count num">{{ num(total) }}</span>
        <Button small icon="add" variant="tonal" :disabled="!feeChambers.length" data-testid="desk-walkin" @click="feeFor = 'walkin'">{{ T.addWalkIn() }}</Button>
      </div>
      <ErrorText :text="err" />
      <SkeletonCards v-if="!day && !q.error" :n="3" :h="56" />
      <ErrorText v-else-if="q.error && !day" :text="errorText(q.error)" />
      <EmptyState v-else-if="!total && !chambers.length" icon="event_outline" :title="T.noSerialsToday()" />
      <template v-else>
      <div v-for="c in chambers" :key="c.chamberKey" class="col gap-8">
        <span v-if="chambers.length > 1" class="t-title-s">{{ c.name }}</span>
        <p v-if="!c.rows.length" class="t-body-m muted">{{ T.noSerialsToday() }}</p>
        <div v-for="r in c.rows" :key="r.id" :class="cx('inv-desk-row', (r.state === 'done' || r.state === 'passed') && 'appt-dim')" data-testid="desk-row">
          <span class="appt-serial-chip num">{{ num(r.serial) }}</span>
          <span class="col gap-2 grow">
            <span class="t-title-s ellipsis">{{ r.name || tr('Deleted account', 'মুছে ফেলা অ্যাকাউন্ট') }}</span>
            <span class="row wrap gap-6">
              <Tag v-if="r.checkedIn || localIn.has(r.id)" tone="info">{{ T.checkedIn() }}</Tag>
              <Tag v-if="paidFor.get(r.id)" tone="good">{{ T.feeTaken() }} · {{ money(paidFor.get(r.id)!.total) }}</Tag>
              <Tag v-else-if="r.feeNo" tone="good">{{ T.feeTaken() }} · {{ num(r.feeNo) }}</Tag>
            </span>
          </span>
          <span class="row wrap gap-6 inv-desk-acts">
            <Button v-if="!r.checkedIn && !localIn.has(r.id) && !paidFor.get(r.id) && !r.feeNo" small variant="outline" :busy="busy === r.id" :disabled="!!busy" data-testid="desk-checkin" @click="checkIn(r)">{{ T.checkIn() }}</Button>
            <Button v-if="paidFor.get(r.id)" small variant="text" @click="receipt = { inv: paidFor.get(r.id)!, print: false }">{{ T.seeReceipt() }}</Button>
            <Button v-else-if="!r.feeNo" small :disabled="!!busy" data-testid="desk-fee" @click="feeFor = r">{{ T.collectFee() }}</Button>
          </span>
        </div>
      </div>
      </template>
    </section>

    <!-- সহকারী: শুধু নিজের রসিদ, ২ ঘণ্টার মধ্যে বাতিল; "দিনের শেষ" আর CSV শুধু ডাক্তার (নিরাপত্তা পর্যালোচনা I7) -->
    <BizInvoiceList v-if="bizId" kind="chamber" :biz-id="bizId" :title="T.todayCollection()" :owner="!doctorUid" :can-void-inv="canVoidInv" />

    <FeeDialog
      v-if="feeFor"
      :biz-id="bizId"
      :doctor-uid="doctorUid"
      :chambers="feeChambers"
      :row="feeFor === 'walkin' ? null : { id: feeFor.id, name: feeFor.name, serial: feeFor.serial, chamberKey: feeFor.chamberKey }"
      @close="feeFor = null"
      @done="feeDone"
    />
    <ReceiptDialog v-if="receipt" :inv="receipt.inv" :auto-print="receipt.print" @close="receipt = null" />
  </div>
</template>
