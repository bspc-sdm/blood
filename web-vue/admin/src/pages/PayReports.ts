// রিপোর্টের পাতার "পেমেন্ট" ট্যাব (backend/docs/PAYMENTS.md, docs/test-plan/PAYMENTS-SECURITY-REVIEW.md):
//   #/payreports?s=open          রোগীর পেমেন্ট রিপোর্ট (admin_pay_reports / admin_pay_report_set) — পেমেন্ট আর তার ইতিহাস
//   #/payreports?s=flags         সতর্কতা (admin_pay_flags): বারবার বাতিল হওয়া রোগী, ৭২ ঘণ্টার বেশি না মেলানো দোকান, "আমি করিনি"-তে
//                                আটকানো নম্বর (freeze), ৭ দিনের বেশি ফেরত পাওনা (refund_due SLA)
// TrxID সবসময় ঢাকা (প্রথম ২ আর শেষ ২ — P12)। Lifolo টাকা রাখে না, ফেরতও দেয় না। নতুন লেখা — বাংলা লাইনে bn-ok।
import { ref } from 'vue';
import { h } from '../h';
import { actionBtn, definePage, table, userLink, type Painter } from '../kit';
import { act, route, setTitle, type Route } from '../state';
import { bn, fmtDT, type J } from '../util';
import { kindTabs } from './ApptReports';

const STATUS: Record<string, string> = { open: 'খোলা', resolved: 'সমাধান হয়েছে', dismissed: 'বাদ দেওয়া' }; // bn-ok
const REASON: Record<string, string> = {
  not_confirmed: 'টাকা দিয়েছেন, দোকান নিশ্চিত করছে না', // bn-ok
  asked_other_number: 'অন্য নম্বরে টাকা চেয়েছে', // bn-ok
  no_refund: 'ফেরত পাননি', // bn-ok
  wrong_number: 'নম্বর ভুল', // bn-ok
  asked_more: 'বেশি টাকা চেয়েছে', // bn-ok
  scam: 'প্রতারণা', // bn-ok
  other: 'অন্য', // bn-ok
};
const PST: Record<string, string> = {
  open: 'খোলা', claimed: 'রোগী জানিয়েছেন', confirmed: 'নিশ্চিত', rejected: 'দোকান পায়নি বলেছে', expired: 'মেয়াদ শেষ', // bn-ok
  refund_due: 'ফেরত পাওনা', refund_claimed: 'ফেরত পাঠিয়েছে বলছে', refunded: 'ফেরত', cancelled: 'বাতিল', // bn-ok
};
const OUTCOME: Record<string, string> = { business_fault: 'দোকানের দোষ', payer_fault: 'রোগীর দোষ', no_fault: 'কারো দোষ নয়' }; // bn-ok
const KIND: Record<string, string> = { pharmacy: 'ফার্মেসি', lab: 'প্যাথলজি', chamber: 'চেম্বার', ambulance: 'অ্যাম্বুলেন্স' }; // bn-ok

/** TrxID ঢাকা: AB••••YZ */
export const maskTrx = (t: unknown) => {
  const s = typeof t === 'string' ? t : '';
  return s.length > 4 ? s.slice(0, 2) + '••••' + s.slice(-2) : s ? '••••' : '—';
};
const taka = (v: unknown) => (v == null ? '—' : '৳' + bn(Number(v).toLocaleString('en-IN')));

/** অবস্থা আর "সতর্কতা" — একই সারিতে */
const statusSeg = (cur: string, n: number | null) => h('div', { class: 'toolbar' },
  h('div', { class: 'seg', role: 'tablist', 'data-testid': 'pay-report-tabs' }, [...Object.entries(STATUS), ['flags', 'সতর্কতা']].map(([k, label]) => // bn-ok
    h('a', { href: `#/payreports?s=${k}`, class: k === cur ? 'on' : null, role: 'tab', 'aria-selected': k === cur ? 'true' : 'false', text: label }))),
  n != null ? h('span', { class: 'count', text: `${bn(n)}টা` }) : null);

function requestCell(q: J | null | undefined) {
  if (!q) return '—';
  const c = q.claim || {};
  return [
    h('div', { text: [q.bizName, KIND[q.kind] || q.kind].filter(Boolean).join(' · ') }),
    h('div', { class: 'small', text: `${taka(q.amount)} · ${PST[q.status] || q.status}` }),
    c.trxId ? h('div', { class: 'small', text: `${c.method || ''} · TrxID ${maskTrx(c.trxId)} · …${bn(c.senderLast4 || '')}` }) : null,
    q.rejectReason ? h('div', { class: 'small', text: `বাতিলের কারণ: ${q.rejectReason}${q.rejectNote ? ' — ' + q.rejectNote : ''}` }) : null, // bn-ok
    q.refund ? h('div', { class: 'small', text: `ফেরত ${taka(q.refund.amount)} · TrxID ${maskTrx(q.refund.trxId)}${q.refund.ackAt ? ' · রোগী পেয়েছেন' : ''}` }) : null, // bn-ok
    q.payerUid ? h('div', { class: 'small' }, userLink(q.payerUid, 'রোগী দেখুন')) : null, // bn-ok
  ];
}

/** রোগীর পেমেন্ট রিপোর্ট */
async function loadPayReports(r: Route, alive: () => boolean): Promise<Painter | void> {
  const st = ['open', 'resolved', 'dismissed'].includes(r.params.get('s') || '') ? (r.params.get('s') as string) : 'open';
  const res = await act('admin_pay_reports', { status: st });
  if (!alive()) return;
  const items = ref<J[]>(res.items || []);
  setTitle('পেমেন্ট রিপোর্ট', `${bn(items.value.length)}টা ${STATUS[st]}`); // bn-ok
  const set = (id: string, status: string, outcome?: string) => () => act('admin_pay_report_set', { id, status, ...(outcome ? { outcome } : {}) });
  const after = () => route(false, true);
  return () => [
    kindTabs('pay'),
    statusSeg(st, items.value.length),
    h('p', { class: 'note', text: 'Lifolo টাকা রাখে না, ফেরতও দেয় না — রোগী সরাসরি দোকান/ডাক্তারের নম্বরে পাঠান। দেখে যোগাযোগ বা ব্যবসা বন্ধের ব্যবস্থা নিন।' }), // bn-ok
    table(items.value, [
      { label: 'কেন', main: true, cell: (x) => [ // bn-ok
        h('div', { class: 'chips' },
          h('span', { class: 'chip bad', text: REASON[x.reason] || x.reason || '—' }),
          x.disputesReject ? h('span', { class: 'chip pri', text: 'বাতিলের আপত্তি' }) : null, // bn-ok
          x.slaBreached ? h('span', { class: 'chip bad', text: '৭২ ঘণ্টা পেরিয়েছে' }) : null, // bn-ok
          x.outcome ? h('span', { class: 'chip', text: OUTCOME[x.outcome] || x.outcome }) : null),
        x.note ? h('div', { class: 'body', text: x.note }) : null,
        x.reporterUid ? h('div', { class: 'small' }, userLink(x.reporterUid)) : null,
        h('div', { class: 'small', text: fmtDT(x.createdAt) }),
      ] },
      { label: 'পেমেন্ট', cell: (x) => requestCell(x.request) }, // bn-ok
      { label: 'ইতিহাস', cell: (x) => ((x.events || []).length // bn-ok
        ? (x.events as J[]).map((e) => h('div', { class: 'small', text: `${fmtDT(e.at)} · ${e.action || ''}${e.from ? ` · ${PST[e.from] || e.from} → ${PST[e.to] || e.to}` : ''}${e.trx ? ` · ${maskTrx(e.trx)}` : ''}` }))
        : '—') },
    ], (x) => (x.status === 'open'
      ? [
        actionBtn('দোকানের দোষ', 'warn', set(x.id, 'resolved', 'business_fault'), 'রিপোর্ট সমাধান হয়েছে', after), // bn-ok
        actionBtn('রোগীর দোষ', 'quiet', set(x.id, 'resolved', 'payer_fault'), 'রিপোর্ট সমাধান হয়েছে', after), // bn-ok
        actionBtn('সমাধান হয়েছে', 'primary', set(x.id, 'resolved', 'no_fault'), 'রিপোর্ট সমাধান হয়েছে', after), // bn-ok
        actionBtn('বাদ দিন', 'quiet', set(x.id, 'dismissed'), 'রিপোর্ট বাদ দেওয়া হয়েছে', after), // bn-ok
      ]
      : [actionBtn('আবার খুলুন', 'quiet', set(x.id, 'open'), 'রিপোর্ট আবার খোলা হয়েছে', after)])), // bn-ok
  ];
}

/** সতর্কতার তালিকা (admin_pay_flags: frozen, refundsOverdue, numbersChanged, reportsLate, disputedRejects, businesses, users) */
async function loadPayFlags(alive: () => boolean): Promise<Painter | void> {
  const res = await act('admin_pay_flags');
  if (!alive()) return;
  const arr = (k: string): J[] => (Array.isArray(res[k]) ? res[k] : []);
  const frozen = arr('frozen');
  const overdue = arr('refundsOverdue');
  const changed = arr('numbersChanged');
  const late = arr('reportsLate');
  const disputed = arr('disputedRejects');
  const businesses = arr('businesses');
  const users = arr('users');
  setTitle('পেমেন্টের সতর্কতা', `${bn(frozen.length + overdue.length + changed.length + late.length + disputed.length + businesses.length + users.length)}টা`); // bn-ok
  const head = (t: string, n: number) => h('h2', { class: 'section-title', text: `${t} (${bn(n)})` });
  const bizCell = (x: J) => [x.bizName || x.bizId || '—', h('div', { class: 'small', text: KIND[x.kind] || x.kind || '' })];
  const after = () => route(false, true);
  return () => [
    kindTabs('pay'),
    statusSeg('flags', null),
    head('"আমি করিনি" — আটকানো পেমেন্ট নম্বর', frozen.length), // bn-ok
    table(frozen, [
      { label: 'ব্যবসা', main: true, cell: bizCell }, // bn-ok
      { label: 'কখন', cell: (x) => fmtDT(x.frozenAt ?? x.frozenSince ?? x.at) }, // bn-ok
      { label: 'কীভাবে', cell: (x) => [x.via === 'email' ? 'ইমেইলের লিংক' : x.via === 'app' ? 'অ্যাপ' : x.via || '—', x.frozenBy ? h('div', { class: 'small' }, userLink(x.frozenBy)) : null] }, // bn-ok
    ], (x) => [actionBtn('আবার চালু করুন', 'quiet', () => act('admin_pay_unfreeze', { kind: x.kind, bizId: x.bizId }), 'নম্বর আবার চালু হয়েছে', after)]), // bn-ok
    head('ফেরত পাওনা ৭ দিনের বেশি', overdue.length), // bn-ok
    table(overdue, [
      { label: 'পেমেন্ট', main: true, cell: (x) => requestCell(x.request ?? x) }, // bn-ok
      { label: 'কবে থেকে', cell: (x) => fmtDT(x.refundDue?.at ?? x.request?.refundDue?.at ?? x.dueSince) }, // bn-ok
    ]),
    head('৭ দিনে নম্বর বদল', changed.length), // bn-ok
    table(changed, [
      { label: 'ব্যবসা', main: true, cell: bizCell }, // bn-ok
      { label: 'বদল', cell: (x) => ((x.changes || [x]) as J[]).map((c) => h('div', { class: 'small', text: `${fmtDT(c.at)} · ${c.method || ''} ${c.change || ''} …${bn(c.oldLast4 || '—')} → …${bn(c.newLast4 || '—')}` })) }, // bn-ok
    ]),
    head('৭২ ঘণ্টায় উত্তর হয়নি এমন রিপোর্ট', late.length), // bn-ok
    table(late, [
      { label: 'কেন', main: true, cell: (x) => [REASON[x.reason] || x.reason || '—', h('div', { class: 'small', text: fmtDT(x.createdAt) })] }, // bn-ok
      { label: 'পেমেন্ট', cell: (x) => requestCell(x.request) }, // bn-ok
    ]),
    head('আপত্তি করা বাতিল', disputed.length), // bn-ok
    table(disputed, [
      { label: 'পেমেন্ট', main: true, cell: (x) => requestCell(x.request ?? x) }, // bn-ok
    ]),
    head('৭২ ঘণ্টার বেশি না মেলানো', businesses.length), // bn-ok
    table(businesses, [
      { label: 'ব্যবসা', main: true, cell: bizCell }, // bn-ok
      { label: 'অপেক্ষায়', cell: (x) => `${bn(x.pendingClaims)}টা` }, // bn-ok
      { label: 'সবচেয়ে পুরোনো', cell: (x) => fmtDT(x.oldestClaimAt) }, // bn-ok
    ]),
    head('৩০ দিনে ৩+ বার বাতিল', users.length), // bn-ok
    table(users, [
      { label: 'রোগী', main: true, cell: (x) => [x.name || '—', h('div', { class: 'small' }, userLink(x.uid))] }, // bn-ok
      { label: 'বাতিল', cell: (x) => `${bn(x.rejected30d)}টা · ${fmtDT(x.lastRejectAt)}` }, // bn-ok
      { label: 'খোলা দাবি', cell: (x) => bn(x.openClaims ?? 0) }, // bn-ok
    ]),
  ];
}

export const PayReports = definePage('PagePayReports', (r, { alive }) => (r.params.get('s') === 'flags' ? loadPayFlags(alive) : loadPayReports(r, alive)));
