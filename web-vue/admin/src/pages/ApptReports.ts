// অনলাইন অ্যাপয়েন্টমেন্ট রিপোর্ট (#/apptreports?s=open) — admin_appt_reports / admin_appt_report_set (backend CHAMBERS-ONLINE.md R8)।
// রোগী/ডাক্তার/সহকারীর রিপোর্ট, অ্যাপয়েন্টমেন্ট, টাকার ইতিহাস আর ডাক্তারের bKash নম্বর বদল। ফিচার সুইচ online_chamber বন্ধ থাকলে
// মেনুতে নেই। নতুন লেখা (পুরোনো প্যানেলে নেই) — তাই বাংলা লাইনে bn-ok।
import { ref } from 'vue';
import { h } from '../h';
import { actionBtn, definePage, table, toolbar, userLink } from '../kit';
import { act, route, setTitle } from '../state';
import { bn, fmtDT, fmtDate, type J } from '../util';

// ---------- ফিচার সুইচ (features action; জানা না গেলে বন্ধ) ----------
const flags = ref<Record<string, boolean> | null>(null);
let asked = false;
export function apptReportsOn(): boolean {
  if (!asked) {
    asked = true;
    act('features')
      .then((r: J) => (flags.value = (r && r.keys) || {}))
      .catch(() => (flags.value = {}));
  }
  return !!flags.value && flags.value.online_chamber === true;
}

const STATUS: Record<string, string> = { open: 'খোলা', resolved: 'সমাধান হয়েছে', dismissed: 'বাদ দেওয়া' }; // bn-ok
const ROLE: Record<string, string> = { patient: 'রোগী', doctor: 'ডাক্তার', provider: 'ডাক্তার', assistant: 'সহকারী', system: 'অ্যাপ নিজে' }; // bn-ok
const PAY: Record<string, string> = { unpaid: 'পাঠানো হয়নি', claimed: 'রোগী পাঠিয়েছেন', paid: 'পরিশোধিত' }; // bn-ok
const pay = (s: unknown) => (typeof s === 'string' ? PAY[s] || s : '—');

export const ApptReports = definePage('PageApptReports', async (r, { alive }) => {
  const st = ['open', 'resolved', 'dismissed'].includes(r.params.get('s') || '') ? (r.params.get('s') as string) : 'open';
  const res = await act('admin_appt_reports', { status: st });
  if (!alive()) return;
  const items = ref<J[]>(res.items || []);
  setTitle('অনলাইন অ্যাপয়েন্টমেন্ট রিপোর্ট', `${bn(items.value.length)}টা ${STATUS[st]}`); // bn-ok
  const set = (id: string, status: string) => () => act('admin_appt_report_set', { id, status });
  const after = () => route(false, true);
  return () => [
    toolbar({ tabs: Object.entries(STATUS) as [string, string][], current: st, base: 'apptreports', count: items.value.length }),
    h('p', { class: 'note', text: 'Lifolo টাকা রাখে না, ফেরতও দেয় না — ফি রোগী সরাসরি ডাক্তারের bKash-এ পাঠান। এখানে দেখে ডাক্তার/রোগীর সাথে যোগাযোগ বা তালিকা বন্ধের ব্যবস্থা নিন।' }), // bn-ok
    table(items.value, [
      { label: 'কে, কেন', main: true, cell: (x) => [ // bn-ok
        h('div', { class: 'chips' }, h('span', { class: 'chip bad', text: ROLE[x.reporterRole] || x.reporterRole || '—' })),
        h('div', { class: 'body', text: x.reason || '—' }),
        x.reporterUid ? h('div', { class: 'small' }, userLink(x.reporterUid)) : null,
        h('div', { class: 'small', text: fmtDT(x.createdAt) }),
      ] },
      { label: 'অ্যাপয়েন্টমেন্ট', cell: (x) => { // bn-ok
        const a = x.appointment || {};
        return [
          h('div', { text: [a.patientName, a.providerName || a.doctorName].filter(Boolean).join(' → ') || '—' }),
          h('div', { class: 'small', text: [a.date ? fmtDate(a.date) : null, a.start || null, a.status || null].filter(Boolean).join(' · ') }),
          h('div', { class: 'small', text: `ফি: ${a.fee || '—'} · ${pay(a.payState)}${a.payRef ? ` · TrxID ${a.payRef}` : ''}` }), // bn-ok
          a.bkashNumber ? h('div', { class: 'small', text: `bKash: ${bn(a.bkashNumber)}` }) : null,
          a.providerUid ? h('div', { class: 'small' }, userLink(a.providerUid, 'ডাক্তার দেখুন')) : null, // bn-ok
        ];
      } },
      { label: 'টাকার ইতিহাস', cell: (x) => ((x.payLog || []).length // bn-ok
        ? (x.payLog as J[]).map((l) => h('div', { class: 'small', text: `${fmtDT(l.at)} · ${ROLE[l.by] || l.by || ''} · ${pay(l.from)} → ${pay(l.to)}${l.payRef ? ` · ${l.payRef}` : ''}${l.note ? ` · ${l.note}` : ''}` }))
        : '—') },
      { label: 'bKash নম্বর বদল', cell: (x) => ((x.bkashChanges || []).length // bn-ok
        ? (x.bkashChanges as J[]).map((b) => h('div', { class: 'small', text: `${fmtDT(b.at)} · …${bn(b.oldLast3 || '—')} → …${bn(b.newLast3 || '—')}` }))
        : '—') },
    ], (x) => (x.status === 'open'
      ? [
        actionBtn('সমাধান হয়েছে', 'primary', set(x.id, 'resolved'), 'রিপোর্ট সমাধান হয়েছে', after), // bn-ok
        actionBtn('বাদ দিন', 'quiet', set(x.id, 'dismissed'), 'রিপোর্ট বাদ দেওয়া হয়েছে', after), // bn-ok
      ]
      : [actionBtn('আবার খুলুন', 'quiet', set(x.id, 'open'), 'রিপোর্ট আবার খোলা হয়েছে', after)])), // bn-ok
  ];
});
