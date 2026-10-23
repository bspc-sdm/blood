// অনলাইন অ্যাপয়েন্টমেন্ট রিপোর্ট (#/apptreports?s=open) — admin_appt_reports / admin_appt_report_set (backend CHAMBERS-ONLINE.md R8)।
// রোগী/ডাক্তার/সহকারীর রিপোর্ট, অ্যাপয়েন্টমেন্ট, টাকার ইতিহাস আর ডাক্তারের bKash নম্বর বদল। ফিচার সুইচ online_chamber বন্ধ থাকলে
// মেনুতে নেই। নতুন লেখা (পুরোনো প্যানেলে নেই) — তাই বাংলা লাইনে bn-ok।
import { ref } from 'vue';
import { h } from '../h';
import { actionBtn, definePage, table, toolbar, userLink } from '../kit';
import { act, route, setTitle } from '../state';
import { bn, fmtDT, fmtDate, type J } from '../util';

// ---------- ফিচার সুইচ: online_chamber বা calls_audio কারো জন্য চালু (পাইলট বা সবার) হলে মেনুতে; জানা না গেলে বন্ধ ----------
// (প্যানেলের কুকি সেশনে `features` চলে না — শুধু admin_*; তাই admin_features_get-এর mode)
const modes = ref<Record<string, string> | null>(null);
let asked = false;
const live = (key: string) => !!modes.value && (modes.value[key] === 'pilot' || modes.value[key] === 'on');
export function apptReportsOn(): boolean {
  if (!asked) {
    asked = true;
    act('admin_features_get')
      .then((r: J) => (modes.value = Object.fromEntries(((r && r.items) || []).map((x: J) => [x.key, x.mode]))))
      .catch(() => (modes.value = {}));
  }
  return live('online_chamber') || live('calls_audio') || live('chat');
}

/** রিপোর্টের ধরন: অনলাইন অ্যাপয়েন্টমেন্ট / কল / মেসেজ */
const kindTabs = (cur: 'appt' | 'call' | 'msg') => h('div', { class: 'seg', role: 'tablist', 'aria-label': 'রিপোর্টের ধরন' }, // bn-ok
  ([['apptreports', 'appt', 'অনলাইন অ্যাপয়েন্টমেন্ট'], ['callreports', 'call', 'কল'], ['msgreports', 'msg', 'মেসেজ']] as const).map(([path, k, label]) => // bn-ok
    h('a', { href: `#/${path}?s=open`, class: k === cur ? 'on' : null, role: 'tab', 'aria-selected': k === cur ? 'true' : 'false', text: label })));

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
    kindTabs('appt'),
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

// ---------- কলের রিপোর্ট (#/callreports?s=open) — admin_call_reports / admin_call_report_set ----------
const CALL_REASON: Record<string, string> = { harassment: 'হয়রানি', spam: 'স্প্যাম', fake: 'ভুয়া', other: 'অন্য কারণ' }; // bn-ok
const person = (p: J) => [p?.name || '—', p?.email ? h('div', { class: 'small', text: p.email }) : null, p?.uid ? h('div', { class: 'small' }, userLink(p.uid)) : null];

export const CallReports = definePage('PageCallReports', async (r, { alive }) => {
  const st = ['open', 'resolved', 'dismissed'].includes(r.params.get('s') || '') ? (r.params.get('s') as string) : 'open';
  const res = await act('admin_call_reports', { status: st });
  if (!alive()) return;
  const items = ref<J[]>(res.items || []);
  setTitle('কলের রিপোর্ট', `${bn(items.value.length)}টা ${STATUS[st]}`); // bn-ok
  const set = (id: string, status: string) => () => act('admin_call_report_set', { id, status });
  const after = () => route(false, true);
  return () => [
    kindTabs('call'),
    toolbar({ tabs: Object.entries(STATUS) as [string, string][], current: st, base: 'callreports', count: items.value.length }),
    h('p', { class: 'note', text: 'যাঁর নামে রিপোর্ট, তাঁর পাতা খুলে অ্যাকাউন্ট বা তালিকার ব্যবস্থা নিন। কলের কথা রেকর্ড হয় না — শুধু কখন, কতক্ষণ, কোন প্রসঙ্গে।' }), // bn-ok
    table(items.value, [
      { label: 'কেন', main: true, cell: (x) => [ // bn-ok
        h('div', { class: 'chips' }, h('span', { class: 'chip bad', text: CALL_REASON[x.reason] || x.reason || '—' })),
        x.note ? h('div', { class: 'body', text: x.note }) : null,
        h('div', { class: 'small', text: fmtDT(x.createdAt) }),
      ] },
      { label: 'যাঁর নামে', cell: (x) => [ // bn-ok
        person(x.reported),
        h('div', { class: 'small', text: `খোলা রিপোর্ট ${bn(x.reported?.openReports || 0)}টা · ${bn(x.reported?.blockedBy || 0)} জন কল বন্ধ করেছেন` }), // bn-ok
      ] },
      { label: 'কে করেছেন', cell: (x) => person(x.reporter) }, // bn-ok
      { label: 'কল', cell: (x) => { // bn-ok
        const c = x.call || {};
        return h('div', { class: 'small', text: [c.video ? 'ভিডিও' : 'অডিও', c.ctx || null, c.durationSec != null ? `${bn(c.durationSec)} সেকেন্ড` : null, c.startedAt ? fmtDT(c.startedAt) : null].filter(Boolean).join(' · ') }); // bn-ok
      } },
    ], (x) => (x.status === 'open'
      ? [
        actionBtn('সমাধান হয়েছে', 'primary', set(x.id, 'resolved'), 'রিপোর্ট সমাধান হয়েছে', after), // bn-ok
        actionBtn('বাদ দিন', 'quiet', set(x.id, 'dismissed'), 'রিপোর্ট বাদ দেওয়া হয়েছে', after), // bn-ok
      ]
      : [actionBtn('আবার খুলুন', 'quiet', set(x.id, 'open'), 'রিপোর্ট আবার খোলা হয়েছে', after)])), // bn-ok
  ];
});

// ---------- মেসেজের রিপোর্ট (#/msgreports?s=open) — admin_chat_reports / admin_chat_report_resolve (CHAT.md) ----------
// রিপোর্ট করা মেসেজের লেখা আর ছবি শুধু অ্যাডমিন দেখেন (রিপোর্টের সময় রাখা কপি — মুছে ফেললেও থাকে)।
const photoSrc = (p: unknown) => (typeof p === 'string' && p ? (p.startsWith('data:') ? p : `data:image/jpeg;base64,${p}`) : null);

export const MessageReports = definePage('PageMessageReports', async (r, { alive }) => {
  const st = ['open', 'resolved', 'dismissed'].includes(r.params.get('s') || '') ? (r.params.get('s') as string) : 'open';
  const res = await act('admin_chat_reports', { status: st });
  if (!alive()) return;
  const items = ref<J[]>(res.items || []);
  setTitle('মেসেজের রিপোর্ট', `${bn(items.value.length)}টা ${STATUS[st]}`); // bn-ok
  const set = (id: string, status: string) => () => act('admin_chat_report_resolve', { id, status });
  const after = () => route(false, true);
  return () => [
    kindTabs('msg'),
    toolbar({ tabs: Object.entries(STATUS) as [string, string][], current: st, base: 'msgreports', count: items.value.length }),
    h('p', { class: 'note', text: 'রিপোর্ট করা মেসেজ (লেখা/ছবি) শুধু এখানে দেখা যায়। যাঁর নামে রিপোর্ট, তাঁর পাতা খুলে ব্যবস্থা নিন।' }), // bn-ok
    table(items.value, [
      { label: 'কেন', main: true, cell: (x) => [ // bn-ok
        h('div', { class: 'chips' }, h('span', { class: 'chip bad', text: CALL_REASON[x.reason] || x.reason || '—' })),
        x.note ? h('div', { class: 'body', text: x.note }) : null,
        h('div', { class: 'small', text: fmtDT(x.createdAt) }),
      ] },
      { label: 'মেসেজ', cell: (x) => { // bn-ok
        const m = x.message || {};
        const src = photoSrc(x.photo);
        return [
          m.text ? h('div', { class: 'body', text: m.text }) : null,
          src ? h('img', { src, alt: 'রিপোর্ট করা ছবি', style: 'max-width:180px;max-height:180px;border-radius:8px;display:block' }) : null, // bn-ok
          m.location ? h('div', { class: 'small', text: `লোকেশন: ${m.location.lat}, ${m.location.lng}` }) : null, // bn-ok
          m.unsent ? h('div', { class: 'small', text: 'পাঠানোর পর মুছে ফেলা হয়েছে' }) : null, // bn-ok
          h('div', { class: 'small', text: [m.ctx || null, m.at ? fmtDT(m.at) : null].filter(Boolean).join(' · ') }),
        ];
      } },
      { label: 'যাঁর নামে', cell: (x) => person(x.reported) }, // bn-ok
      { label: 'কে করেছেন', cell: (x) => person(x.reporter) }, // bn-ok
    ], (x) => (x.status === 'open'
      ? [
        actionBtn('সমাধান হয়েছে', 'primary', set(x.id, 'resolved'), 'রিপোর্ট সমাধান হয়েছে', after), // bn-ok
        actionBtn('বাদ দিন', 'quiet', set(x.id, 'dismissed'), 'রিপোর্ট বাদ দেওয়া হয়েছে', after), // bn-ok
      ]
      : null)),
  ];
});
