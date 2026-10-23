<script setup lang="ts">
// "আজকের রোগী" (আমার কাজ CONTRACT §২) — ডাক্তার (/work/pro) আর সহকারী (/work/rx?doctor=) একই সারি। আগের দুই সারি
// "আজকের সিরিয়াল" (QueueChamberCard: শুরু/পরের রোগী/বিরতি, দেরির উত্তর) আর "আজকের ডেস্ক" (ChamberDesk: এসেছেন, ফি → রসিদ,
// সরাসরি আসা রোগী, আজকের আদায়) এক জায়গায়।
// প্রতিটা সারি: সিরিয়াল, নাম, অবস্থা (অপেক্ষায় / দেখা হচ্ছে / শেষ), একটাই মূল বোতাম — ডাকুন → প্রেসক্রিপশন লিখুন (সহকারী: ফি নিন);
// দেরির অনুরোধে "ঠিক আছে"; বাকি সব (এসেছেন, ফি নিন/রসিদ, পরে আসুন, আগের প্রেসক্রিপশন) "আরও"-তে। প্রেসক্রিপশনের লিংক সারিতে একবারই।
// সহকারী প্রেসক্রিপশন লেখেন না। ডেস্কের অংশ (এসেছেন, ফি, সরাসরি আসা, আদায়) শুধু inv_chamber চালু থাকলে।
// ডেস্কটপে (৯৬০px+) তালিকা-বিস্তারিত: বাঁয়ে সারি, ডানে বাছা রোগী আর তাঁর সব কাজ।
// Tauri ডেস্কটপে (OfflineStore) রোগীর তালিকা স্থানীয় (chamberDay) — চেক-ইন আর ফি অফলাইনে লেখা হয়ে পরে মেলে; সারি চালানো অনলাইনে।
import { computed, reactive, ref, shallowRef, watch } from 'vue';
import { Button, ErrorText, Icon, RowMenu, SkeletonCards, Tag, cx, toast, type MenuItem } from '@/ui/kit';
import { call, errorText } from '@/lib/api';
import { useCall, useQ } from '@/lib/query';
import { isBangla, num, toAsciiDigits, tr } from '@/lib/i18n';
import { useSession } from '@/lib/session';
import { useNav } from '@/lib/nav';
import { useTwoPane } from '@/shell/chrome';
import { REFRESH, lateText, type QueueChamber, type QueueRow } from './queue';
import { T } from '../invoices/text';
import { invNo, money, parsePage, todayStr, type Invoice } from '../invoices/model';
import { errText, issue } from '../invoices/actions';
import { useOffline } from '../invoices/offline';
import { invFeature } from '../invoices/features';
import OfflineBar from '../invoices/OfflineBar.vue';
import FeeDialog, { type DeskChamber } from '../invoices/FeeDialog.vue';
import ReceiptDialog from '../invoices/ReceiptDialog.vue';
import BizInvoiceList from '../invoices/BizInvoiceList.vue';

interface Row { id: string; serial: number; name: string; state: string; checkedIn: boolean; chamberKey: string; feeNo: string; late: QueueRow['late'] }
interface Ch { chamberKey: string; name: string; fees: DeskChamber['fees']; rows: Row[]; q: QueueChamber | null }
/** doctorUid: সহকারী কার হয়ে (ডাক্তার নিজে হলে null); canWrite: প্রেসক্রিপশন লেখেন কি না (শুধু ডাক্তার) */
const props = defineProps<{ doctorUid: string | null; canWrite: boolean; rxLink?: (path: string, q?: Record<string, string>) => string }>();
const s = useSession();
const nav = useNav();
const two = useTwoPane();
const desk = computed(() => invFeature('inv_chamber'));
const bizId = computed(() => props.doctorUid ?? s.uid ?? '');
const off = useOffline('chamber', bizId);
const local = computed(() => off.st.desktop && desk.value);
const withDoc = () => (props.doctorUid ? { doctorUid: props.doctorUid } : {});

const q = useCall<{ role: string; chambers: QueueChamber[] }>('queue_today', withDoc, { refetchInterval: REFRESH, staleTime: 10_000 });
const localDay = shallowRef<any>(null);
async function loadLocal() {
  const st = off.store();
  if (st) localDay.value = await st.chamberDay(todayStr());
}
watch(() => off.st.ready, (r) => r && local.value && loadLocal(), { immediate: true });

const rowOf = (r: any, chamberKey: string, qr?: QueueRow): Row => ({
  id: String(r.id), serial: Number(r.serial) || 0, name: String(r.name ?? r.patientName ?? ''), state: String(qr?.state ?? r.state ?? r.status ?? ''),
  checkedIn: r.checkedIn === true || !!r.arrivedAt || r.status === 'checkedin' || r.status === 'arrived', chamberKey: String(r.chamberKey ?? chamberKey),
  feeNo: String(r.feeNo ?? ''), late: qr?.late ?? r.late ?? null,
});
const chambers = computed<Ch[]>(() => {
  const qc = q.data?.chambers ?? [];
  const qBy = new Map(qc.map((c) => [c.chamberKey, c]));
  const qRow = new Map(qc.flatMap((c) => c.rows.map((r) => [r.id, r] as const)));
  if (local.value) {
    const raw = localDay.value;
    if (!raw) return [];
    const by = new Map<string, any[]>();
    for (const a of raw.appointments ?? []) by.set(a.chamberKey, [...(by.get(a.chamberKey) ?? []), a]);
    const info = new Map<string, any>((raw.chambers ?? []).map((c: any) => [c.chamberKey, c]));
    for (const k of info.keys()) if (!by.has(k)) by.set(k, []);
    return [...by.entries()].map(([k, rows]) => {
      const c = info.get(k);
      return {
        chamberKey: k, name: isBangla() && c?.nameBn ? c.nameBn : c?.name ?? k, fees: c?.fees ?? raw.fees ?? null, q: qBy.get(k) ?? null,
        rows: rows.map((r) => rowOf(r, k, qRow.get(String(r.id)))).filter((r) => r.state !== 'cancelled').sort((x, y) => x.serial - y.serial),
      };
    });
  }
  return qc.map((c: any) => ({
    chamberKey: String(c.chamberKey ?? c.key ?? ''), name: isBangla() && c.nameBn ? c.nameBn : String(c.name ?? ''), fees: c.fees ?? null, q: c,
    rows: (c.rows ?? []).map((r: any) => rowOf(r, c.chamberKey, r)).filter((r: Row) => r.state !== 'cancelled'),
  }));
});
const loading = computed(() => (local.value ? !localDay.value : q.isLoading));
const total = computed(() => chambers.value.reduce((a, c) => a + c.rows.length, 0));

// ফি নেওয়ার চেম্বার (সরাসরি আসা রোগীসহ): assistant_chambers — ডাক্তারের সব চেম্বার আর ফি
const ac = useCall<{ chambers?: { chamberKey: string; name: string; nameBn?: string; fee?: string; noFee?: boolean }[] }>('assistant_chambers', withDoc, () => ({ enabled: desk.value && !off.st.desktop, staleTime: 5 * 60_000 }));
const feeNum = (f?: string) => {
  const m = toAsciiDigits(f ?? '').replace(/,/g, '').match(/\d+(\.\d+)?/);
  return m ? Number(m[0]) : null;
};
const feeChambers = computed<DeskChamber[]>(() => {
  const list: DeskChamber[] = (ac.data?.chambers ?? []).map((c) => {
    const n = c.noFee ? 0 : feeNum(c.fee);
    return { chamberKey: c.chamberKey, name: isBangla() && c.nameBn ? c.nameBn : c.name, fees: n != null ? { new: n } : null };
  });
  for (const c of chambers.value) if (!list.some((x) => x.chamberKey === c.chamberKey)) list.push({ chamberKey: c.chamberKey, name: c.name, fees: c.fees ?? null });
  return list.map((c) => ({ ...c, fees: { ...(chambers.value.find((x) => x.chamberKey === c.chamberKey)?.fees ?? {}), ...(c.fees ?? {}) } }));
});
// আজকের রসিদ (ফি নেওয়া হয়েছে কি না)
const todays = useQ(() => ({
  queryKey: ['invoice_list_biz', 'chamber', bizId.value, 'today', 'desk'],
  queryFn: () => call('invoice_list_biz', { kind: 'chamber', bizId: bizId.value, period: 'today' }).then(parsePage),
  enabled: desk.value && !!bizId.value && !off.st.desktop,
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
const feeDoneFor = (r: Row) => !!paidFor.value.get(r.id) || !!r.feeNo;
const isIn = (r: Row) => r.checkedIn || localIn.has(r.id);

const busy = ref<string | null>(null);
const err = ref<string | null>(null);
async function run(key: string, action: string, b: Record<string, unknown>, done?: string) {
  busy.value = key;
  err.value = null;
  try {
    const r = await call<{ stale?: boolean }>(action, { ...withDoc(), ...b });
    // stale: অন্য কেউ (ডাক্তার/সহকারী) এইমাত্র বদলেছেন — শুধু নতুন অবস্থা দেখানো
    if (done && !r?.stale) toast(done);
    q.refetch();
  } catch (e) {
    err.value = errorText(e);
  } finally {
    busy.value = null;
  }
}
// expect = এখন যে নম্বর দেখছি (০ = কেউ না); date = এই সারির দিন
const control = (c: Ch, op: string, more: Record<string, unknown> = {}, done?: string) => {
  const qs = c.q?.queue;
  return run(op + (more.appointmentId ?? '') + c.chamberKey, 'queue_control', {
    chamberKey: c.chamberKey, op, expect: qs?.started ? qs.current || 0 : 0, ...(c.q?.date ? { date: c.q.date } : {}), ...more,
  }, done);
};
async function checkIn(r: Row) {
  busy.value = 'in' + r.id;
  err.value = null;
  try {
    await issue('chamber_checkin', { appointmentId: r.id, ...withDoc() }, () => ({ type: 'checkin', data: { appointmentId: r.id } }));
    localIn.add(r.id);
    toast(T.checkedIn() + ': ' + r.name);
    if (!off.st.desktop) q.refetch();
  } catch (e) {
    err.value = errText(e);
  } finally {
    busy.value = null;
  }
}
const feeFor = ref<Row | 'walkin' | null>(null);
const receipt = ref<{ inv: Invoice; print: boolean } | null>(null);
function feeDone(inv: Invoice | null) {
  const f = feeFor.value;
  if (f && f !== 'walkin' && inv) localPaid.set(f.id, inv);
  if (f && f !== 'walkin') localIn.add(f.id);
  feeFor.value = null;
  if (inv) receipt.value = { inv, print: true };
  if (off.st.desktop) loadLocal();
}
const ASSIST_VOID_MS = 2 * 3600 * 1000;
const canVoidInv = (i: Invoice) => !props.doctorUid || (i.issuedBy === s.uid && Date.now() - i.issuedAt < ASSIST_VOID_MS);

// ---------- একটা সারির অবস্থা আর কাজ ----------
const isNow = (c: Ch, r: Row) => !!c.q?.queue.started && c.q.queue.current === r.serial;
const isDone = (r: Row) => r.state === 'done' || r.state === 'passed';
const asked = (r: Row) => r.late?.status === 'asked' && (r.state === 'waiting' || r.state === 'later');
const stateText = (c: Ch, r: Row) => (isNow(c, r) ? tr('Being seen', 'দেখা হচ্ছে') : isDone(r) ? tr('Done', 'শেষ') : tr('Waiting', 'অপেক্ষায়')); // bn-ok
const stateTone = (c: Ch, r: Row) => (isNow(c, r) ? 'good' : isDone(r) ? undefined : 'info');
const writeTo = (r: Row) => (props.rxLink ? props.rxLink('/write', { appointment: r.id }) : `/work/rx/write?appointment=${encodeURIComponent(r.id)}`);
const historyTo = (r: Row) => (props.rxLink ? props.rxLink('', { appointment: r.id }) : `/work/rx?appointment=${encodeURIComponent(r.id)}`);
interface Act { key: string; label: string; icon: string; testid?: string; danger?: boolean; run: () => void }
function actsOf(c: Ch, r: Row): { primary: Act | null; more: Act[] } {
  const callIt: Act = { key: 'call', label: tr('Call in', 'ডাকুন'), icon: 'chevron_right', testid: 'tq-call', run: () => control(c, 'set', { appointmentId: r.id }, tr(`Seeing now: #${r.serial}`, `এখন দেখছি: ${num(r.serial)}`)) }; // bn-ok
  const write: Act = { key: 'write', label: tr('Write a prescription', 'প্রেসক্রিপশন লিখুন'), icon: 'edit_note', testid: 'tq-write', run: () => nav(writeTo(r)) };
  const ok: Act = { key: 'ok', label: tr('OK', 'ঠিক আছে'), icon: 'check_circle', testid: 'tq-late-ok', run: () => run('ok' + r.id, 'appointment_late_reply', { id: r.id, op: 'ok' }) };
  const later: Act = { key: 'later', label: tr('Come later', 'পরে আসুন'), icon: 'schedule', testid: 'tq-late-later', run: () => run('later' + r.id, 'appointment_late_reply', { id: r.id, op: 'later' }) };
  const fee: Act = { key: 'fee', label: T.collectFee(), icon: 'payments', testid: 'desk-fee', run: () => (feeFor.value = r) };
  const rec: Act | null = paidFor.value.get(r.id) ? { key: 'rec', label: T.seeReceipt(), icon: 'list_alt', testid: 'desk-receipt', run: () => (receipt.value = { inv: paidFor.value.get(r.id)!, print: false }) } : null;
  const inA: Act = { key: 'in', label: T.checkIn(), icon: 'check_circle', testid: 'desk-checkin', run: () => checkIn(r) };
  const hist: Act = { key: 'hist', label: tr('Earlier prescriptions', 'আগের প্রেসক্রিপশন'), icon: 'history', testid: 'tq-history', run: () => nav(historyTo(r)) };
  const now = isNow(c, r);
  const primary: Act | null = asked(r) ? ok : now ? (props.canWrite ? write : desk.value && !feeDoneFor(r) ? fee : null) : isDone(r) ? null : callIt;
  const rest: (Act | null | false)[] = [
    asked(r) && later,
    asked(r) && !now && !isDone(r) && callIt,
    // দেখা হয়ে যাওয়া রোগীকে আবার ডাকা যায় (আগের "সিরিয়াল চেপে এখন দেখছি"-র মতো)
    isDone(r) && callIt,
    desk.value && !isIn(r) && !feeDoneFor(r) && !isDone(r) && inA,
    desk.value && !feeDoneFor(r) && fee,
    desk.value && rec,
    props.canWrite && write,
    props.canWrite && hist,
  ];
  const more = rest.filter((x): x is Act => !!x).filter((x, i, a) => x.key !== primary?.key && a.findIndex((y) => y.key === x.key) === i);
  return { primary, more };
}
const menuOf = (acts: Act[]): MenuItem[] => acts.map((a) => ({ label: a.label, icon: a.icon, testid: a.testid, danger: a.danger, disabled: !!busy.value, onClick: a.run }));
const statusOf = (c: Ch) => {
  const qs = c.q?.queue;
  return !qs ? null : qs.paused ? tr('Break', 'বিরতি') : qs.finished ? tr('Done', 'শেষ') : qs.started ? tr('Running', 'চলছে') : tr('Not started', 'শুরু হয়নি');
};
// ডেস্কটপে বাছা রোগী (না বাছলে এখন দেখা রোগী, নইলে প্রথম অপেক্ষায় থাকা)
const picked = ref<string | null>(null);
const all = computed(() => chambers.value.flatMap((c) => c.rows.map((r) => ({ c, r }))));
const sel = computed(() => all.value.find((x) => x.r.id === picked.value) ?? all.value.find((x) => isNow(x.c, x.r)) ?? all.value.find((x) => !isDone(x.r)) ?? all.value[0] ?? null);
const many = computed(() => chambers.value.length > 1);
</script>

<template>
  <div class="col gap-16" data-testid="today-queue">
    <OfflineBar v-if="desk" :st="off.st" :sync="off.syncNow" />
    <div class="row wrap gap-8">
      <h2 class="t-title-m grow">{{ tr('Today\'s patients', 'আজকের রোগী') }}<span v-if="total" class="rxp-count num tq-total">{{ num(total) }}</span></h2><!-- bn-ok -->
      <Button v-if="desk" small icon="add" variant="tonal" :disabled="!feeChambers.length" data-testid="desk-walkin" @click="feeFor = 'walkin'">{{ T.addWalkIn() }}</Button>
    </div>
    <ErrorText :text="err" />
    <SkeletonCards v-if="loading && !q.error" :n="3" :h="56" />
    <ErrorText v-else-if="q.error && !chambers.length" :text="errorText(q.error)" />
    <div v-else-if="!chambers.length" class="q-empty-line" data-testid="tq-empty"><Icon name="event_outline" /><span class="t-body-m">{{ T.noSerialsToday() }}</span></div>
    <div v-else :class="two ? 'qd' : 'col gap-16'">
      <div :class="two ? 'qd-list' : 'col gap-16'">
        <section v-for="c in chambers" :key="c.chamberKey" class="card col gap-12" :aria-label="c.name || tr('Today\'s patients', 'আজকের রোগী')" data-testid="tq-chamber"><!-- bn-ok -->
          <div class="tq-head">
            <span class="col gap-2 grow">
              <span v-if="many" class="t-title-s">{{ c.name }}</span>
              <span v-if="c.q" class="tq-nums t-body-s muted">
                <span>{{ tr('Seeing now ', 'এখন দেখছি ') }}<b class="big num">{{ c.q.queue.started && c.q.queue.current ? num(c.q.queue.current) : '—' }}</b></span>
                <span>{{ tr('Next ', 'পরের ') }}<b class="num">{{ c.q.queue.next ? num(c.q.queue.next) : '—' }}</b></span>
                <span>{{ tr('Avg. ', 'গড় ') }}<b class="num">{{ tr(`${c.q.avgMin}m`, `${num(c.q.avgMin)} মি.`) }}</b></span>
              </span>
            </span>
            <Tag v-if="statusOf(c)" :tone="c.q?.queue.paused ? 'primary' : c.q?.queue.started && !c.q?.queue.finished ? 'good' : undefined">{{ statusOf(c) }}</Tag>
          </div>
          <div v-if="c.q" class="q-acts">
            <Button v-if="!c.q.queue.started" icon="chevron_right" :busy="busy === 'start' + c.chamberKey" :disabled="!!busy" data-testid="tq-start" @click="control(c, 'start')">{{ tr('Start', 'শুরু করুন') }}</Button>
            <Button v-else icon="chevron_right" :busy="busy === 'next' + c.chamberKey" :disabled="!!busy || (!c.q.queue.next && c.q.queue.finished)" data-testid="tq-next" @click="control(c, 'next')">{{ tr('Next patient', 'পরের রোগী') }}</Button>
            <span class="grow" />
            <RowMenu
              v-if="c.q.queue.started"
              testid="tq-chamber-more"
              :items="[
                c.q.queue.paused
                  ? { label: tr('Resume', 'আবার শুরু'), icon: 'sync', disabled: !!busy, testid: 'tq-resume', onClick: () => control(c, 'resume') }
                  : { label: tr('Break', 'বিরতি'), icon: 'schedule', disabled: !!busy || c.q.queue.finished, testid: 'tq-pause', onClick: () => control(c, 'pause') },
              ]"
            />
          </div>
          <p v-if="!c.rows.length" class="t-body-m muted">{{ T.noSerialsToday() }}</p>
          <div v-else class="tq-rows">
            <div
              v-for="r in c.rows"
              :key="r.id"
              :class="cx('tq-row', isNow(c, r) && 'now', isDone(r) && 'appt-dim', two && sel?.r.id === r.id && 'sel')"
              data-testid="desk-row"
            >
              <component :is="two ? 'button' : 'div'" :type="two ? 'button' : undefined" class="tq-pick" :aria-current="two ? sel?.r.id === r.id : undefined" :data-testid="two ? 'q-item' : undefined" @click="two && (picked = r.id)">
                <span class="appt-serial-chip num">{{ num(r.serial) }}</span>
                <span class="tq-name">
                  <span class="t-title-s ellipsis">{{ r.name || tr('Deleted account', 'মুছে ফেলা অ্যাকাউন্ট') }}</span>
                  <span class="row wrap gap-6">
                    <Tag :tone="stateTone(c, r)" data-testid="tq-state">{{ stateText(c, r) }}</Tag>
                    <Tag v-if="r.state === 'later'">{{ tr('At the end', 'শেষে') }}</Tag>
                    <Tag v-if="r.late && (asked(r) || r.state === 'later' || r.late.status === 'ok') && !isDone(r)" :tone="asked(r) ? 'error' : 'info'">{{ lateText(r.late.min) }}</Tag>
                    <Tag v-if="desk && isIn(r)" tone="info">{{ T.checkedIn() }}</Tag>
                    <Tag v-if="desk && paidFor.get(r.id)" tone="good">{{ T.feeTaken() }} · {{ money(paidFor.get(r.id)!.total) }}</Tag>
                    <Tag v-else-if="desk && r.feeNo" tone="good">{{ T.feeTaken() }} · {{ invNo(r.feeNo) }}</Tag>
                  </span>
                  <span v-if="asked(r) && r.late?.note" class="t-body-s muted">{{ r.late.note }}</span>
                </span>
              </component>
              <span v-if="!two" class="tq-acts">
                <Button v-if="actsOf(c, r).primary" small :busy="!!busy && busy.includes(r.id)" :disabled="!!busy" :data-testid="actsOf(c, r).primary!.testid" @click="actsOf(c, r).primary!.run()">{{ actsOf(c, r).primary!.label }}</Button>
                <RowMenu :items="menuOf(actsOf(c, r).more)" />
              </span>
            </div>
          </div>
        </section>
      </div>
      <!-- ডেস্কটপ: বাছা রোগী আর তাঁর সব কাজ -->
      <aside v-if="two" class="qd-side" :aria-label="tr('Details', 'বিস্তারিত')">
        <article v-if="sel" class="card col gap-12 tq-detail q-detail" data-testid="tq-detail">
          <div class="row gap-8">
            <span class="appt-serial-chip num">{{ num(sel.r.serial) }}</span>
            <span class="t-title-l grow">{{ sel.r.name || tr('Deleted account', 'মুছে ফেলা অ্যাকাউন্ট') }}</span>
            <Tag :tone="stateTone(sel.c, sel.r)">{{ stateText(sel.c, sel.r) }}</Tag>
          </div>
          <span v-if="many" class="icon-line"><Icon name="local_hospital" /><span>{{ sel.c.name }}</span></span>
          <span v-if="sel.r.late && !isDone(sel.r)" class="icon-line"><Icon name="schedule" /><span>{{ lateText(sel.r.late.min) + (sel.r.late.note ? ' · ' + sel.r.late.note : '') }}</span></span>
          <div class="row wrap gap-8">
            <Button v-if="actsOf(sel.c, sel.r).primary" :icon="actsOf(sel.c, sel.r).primary!.icon" :busy="!!busy && busy.includes(sel.r.id)" :disabled="!!busy" :data-testid="actsOf(sel.c, sel.r).primary!.testid" @click="actsOf(sel.c, sel.r).primary!.run()">{{ actsOf(sel.c, sel.r).primary!.label }}</Button>
            <Button v-for="a in actsOf(sel.c, sel.r).more" :key="a.key" variant="outline" :icon="a.icon" :disabled="!!busy" :data-testid="a.testid" @click="a.run()">{{ a.label }}</Button>
          </div>
        </article>
      </aside>
    </div>

    <!-- আজকের আদায় — সহকারী: শুধু নিজের রসিদ, ২ ঘণ্টার মধ্যে বাতিল; "দিনের শেষ" আর CSV শুধু ডাক্তার (নিরাপত্তা পর্যালোচনা I7) -->
    <BizInvoiceList v-if="desk && bizId" kind="chamber" :biz-id="bizId" :title="T.todayCollection()" :owner="!doctorUid" :can-void-inv="canVoidInv" />

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
