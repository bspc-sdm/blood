// ড্যাশবোর্ড: অপেক্ষার কাজ, এক নজরে সংখ্যা, সাম্প্রতিক অ্যাডমিন কাজ
import { shallowRef } from 'vue';
import { h, icon, type Kid } from '../h';
import { definePage, kpi, logTable } from '../kit';
import { act, getStats, pendingCount, setTitle } from '../state';
import { bn, errMsg, num, type J } from '../util';

export const QUEUES: [string, string, string, string][] = [
  ['badges', 'badges', 'ব্যাজের আবেদন', 'verified'],
  ['pharmacies', 'pharmacies', 'ফার্মেসি অনুমোদন', 'store'],
  ['ambulances', 'ambulances', 'অ্যাম্বুলেন্স অনুমোদন', 'ambulance'],
  ['bloodBanks', 'bloodBanks', 'ব্লাড ব্যাংক অনুমোদন', 'blood'],
  ['labs', 'labs', 'প্যাথলজি অনুমোদন', 'hospital'],
  ['hospitalRequests', 'hospitals', 'হাসপাতাল যোগের অনুরোধ', 'hospital'],
  ['tickets', 'tickets', 'উত্তরের অপেক্ষায় আপিল', 'help'],
  ['reports', 'reports', 'রিপোর্ট', 'flag'],
  ['corrections', 'corrections', 'তারিখ সংশোধন', 'calendar'],
];

export default definePage('PageDashboard', () => {
  const total = QUEUES.reduce((a, [k]) => a + pendingCount(k), 0);
  setTitle('ড্যাশবোর্ড', total ? `অপেক্ষায় ${bn(total)}টা কাজ` : 'অপেক্ষায় কোনো কাজ নেই');
  const kpis = shallowRef<Kid>(h('p', { class: 'note', text: 'হিসাব আসছে…' }));
  const logs = shallowRef<Kid>(h('p', { class: 'note', text: 'লোড হচ্ছে…' }));

  getStats(false).then((st: J) => {
    const t = st.totals || {};
    const u = st.users || {};
    const donors = Object.values(st.bloodGroups || {}).reduce((a: number, b) => a + ((b as number) || 0), 0);
    kpis.value = [
      kpi('মোট ব্যবহারকারী', u.total, u.new7 != null ? `গত ৭ দিনে নতুন ${bn(u.new7)}` : null),
      kpi('গত ৭ দিনে সক্রিয়', u.active7, u.active30 != null ? `৩০ দিনে ${bn(u.active30)}` : null),
      kpi('রক্তদাতা (গ্রুপ দেওয়া)', donors),
      kpi('চালু রক্তের অনুরোধ', t.openRequests, t.allRequests != null ? `মোট ${num(t.allRequests)}` : null),
      kpi('অনুমোদিত ডাক্তার', t.doctors),
      kpi('অনুমোদিত নার্স', t.nurses),
      kpi('বাসায় যান: ডাক্তার', t.homeDoctors),
      kpi('বাসায় যান: নার্স', t.homeNurses),
      kpi('তালিকায় ফার্মেসি', t.pharmacies),
      kpi('তালিকায় অ্যাম্বুলেন্স', t.ambulances),
      kpi('তালিকায় ব্লাড ব্যাংক', t.bloodBanks)];
  }).catch((e) => { kpis.value = h('p', { class: 'error', text: errMsg(e) }); });

  act('admin_log').then((res: J) => {
    logs.value = logTable((res.items || []).slice(0, 6));
  }).catch((e) => { logs.value = h('p', { class: 'error', text: errMsg(e) }); });

  return () => [
    h('h2', { class: 'section-title', text: 'অপেক্ষায় যা আছে' }),
    h('div', { class: 'grid tiles' }, QUEUES.map(([key, path, label, ic]) => {
      const n = pendingCount(key);
      return h('a', { class: `queue${n ? '' : ' zero'}`, href: `#/${path}` },
        h('div', { class: 'badge-ic' }, icon(ic)),
        h('div', null, h('div', { class: 'num', text: bn(n) }), h('div', { class: 'lbl', text: label })));
    })),
    h('h2', { class: 'section-title' }, 'এক নজরে', h('a', { class: 'more', href: '#/analytics', text: 'বিস্তারিত অ্যানালিটিক্স' })),
    h('div', { class: 'grid tiles' }, kpis.value),
    h('h2', { class: 'section-title' }, 'সাম্প্রতিক অ্যাডমিন কাজ', h('a', { class: 'more', href: '#/log', text: 'সব দেখুন' })),
    h('div', null, logs.value),
  ];
});
