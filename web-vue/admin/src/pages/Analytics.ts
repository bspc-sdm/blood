// অ্যানালিটিক্স: গত ৩০ দিন
import { shallowRef } from 'vue';
import { h } from '../h';
import { actionBtn, definePage, kpi } from '../kit';
import { getStats, setTitle } from '../state';
import { L, ago, bn, dayFmt, num, type J } from '../util';
import { barCard, hbars } from './charts';

export default definePage('PageAnalytics', async (_r, { alive }) => {
  const st = shallowRef<J>(null);
  async function load(fresh: boolean) {
    setTitle('অ্যানালিটিক্স', 'গত ৩০ দিন');
    const got = await getStats(fresh);
    if (!alive()) return;
    setTitle('অ্যানালিটিক্স', `হিসাব ${ago(got.at)}`, () => [
      actionBtn('নতুন করে হিসাব', 'quiet', () => load(true), null, null, got._got),
    ]);
    st.value = got;
  }
  await load(false);

  return () => {
    const s = st.value;
    const t = s.totals || {};
    const u = s.users || {};
    const days: number[] = s.days || [];
    const donors = Object.values(s.bloodGroups || {}).reduce((a: number, b) => a + ((b as number) || 0), 0);
    const SERIES = ([
      ['newUsers', 'নতুন ব্যবহারকারী'],
      ['requests', 'রক্তের অনুরোধ'],
      ['homeCalls', 'বাসায় সেবায় নতুন যোগাযোগ'],
      ['appointments', 'অ্যাপয়েন্টমেন্টের অনুরোধ'],
    ] as [string, string][]).filter(([k]) => Array.isArray(s.series && s.series[k]));

    const groups = Object.entries(s.bloodGroups || {}) as [string, number][];
    const statusCard = (title: string, map: Record<string, number> | undefined, labels: Record<string, string>, doneKey: string, endKeys: string[]) => {
      const entries = Object.entries(map || {}).filter(([, v]) => v != null);
      const ended = endKeys.reduce((a, k) => a + ((map || {})[k] || 0), 0);
      const rate = ended ? Math.round((((map || {})[doneKey] || 0) / ended) * 100) : null;
      return h('div', { class: 'card chart-card' }, h('h3', { text: title }),
        h('div', { class: 'note', text: rate != null ? `শেষ হওয়াগুলোর ${bn(rate)}% সম্পন্ন` : 'এখনো কিছু শেষ হয়নি' }),
        hbars(entries.map(([k, v]) => [labels[k] || k, v])));
    };

    return [
      h('h2', { class: 'section-title', text: 'ব্যবহারকারী' }),
      h('div', { class: 'grid tiles' },
        kpi('মোট ব্যবহারকারী', u.total),
        kpi('গত ৭ দিনে নতুন', u.new7),
        kpi('গত ৩০ দিনে নতুন', u.new30),
        kpi('গত ৭ দিনে সক্রিয়', u.active7),
        kpi('গত ৩০ দিনে সক্রিয়', u.active30),
        kpi('রক্তদাতা (গ্রুপ দেওয়া)', donors, t.usersWithProfile != null ? `প্রোফাইল আছে ${num(t.usersWithProfile)} জনের` : null),
        kpi('বন্ধ করা অ্যাকাউন্ট', u.disabled)),
      h('h2', { class: 'section-title', text: 'দৈনিক কার্যক্রম (গত ৩০ দিন)' }),
      h('div', { class: 'grid three' }, SERIES.map(([k, label]) => barCard(label, s.series[k], days))),
      h('details', { class: 'data' }, h('summary', { text: 'সংখ্যাগুলো টেবিলে দেখুন' }),
        h('div', { class: 'card table-wrap' }, h('table', { class: 't' },
          h('thead', null, h('tr', null, h('th', { text: 'তারিখ' }), SERIES.map(([, l]) => h('th', { text: l })))),
          h('tbody', null, days.slice().reverse().map((d, ri) => {
            const i = days.length - 1 - ri;
            return h('tr', null, h('td', { text: dayFmt.format(new Date(d)) }), SERIES.map(([k, l]) => h('td', { 'data-label': l, text: num(s.series[k][i]) })));
          }))))),
      h('h2', { class: 'section-title', text: 'সেবা ও তালিকা' }),
      h('div', { class: 'grid tiles' },
        kpi('চালু রক্তের অনুরোধ', t.openRequests, t.allRequests != null ? `সব মিলিয়ে ${num(t.allRequests)}` : null),
        kpi('অনুমোদিত ডাক্তার', t.doctors),
        kpi('অনুমোদিত নার্স', t.nurses),
        kpi('বাসায় যান: ডাক্তার', t.homeDoctors),
        kpi('বাসায় যান: নার্স', t.homeNurses),
        kpi('পুষ্টিবিদ ও ট্রেইনার', (t.nutritionists || 0) + (t.trainers || 0)),
        kpi('তালিকায় ফার্মেসি', t.pharmacies),
        kpi('তালিকায় অ্যাম্বুলেন্স', t.ambulances),
        kpi('তালিকায় ব্লাড ব্যাংক', t.bloodBanks),
        kpi('আলোচনার প্রশ্ন', t.questions),
        kpi('স্বাস্থ্য টিপস', t.tips)),
      h('h2', { class: 'section-title', text: 'অবস্থা অনুযায়ী (সব সময়ের)' }),
      h('div', { class: 'grid three' },
        h('div', { class: 'card chart-card' }, h('h3', { text: 'রক্তের গ্রুপ অনুযায়ী দাতা' }), hbars(groups)),
        statusCard('অ্যাপয়েন্টমেন্ট', s.byStatus && s.byStatus.appointments, L.appt, 'confirmed', ['confirmed', 'declined', 'cancelled', 'expired'])),
      h('p', { class: 'note', text: 'হিসাব ৩০ মিনিট পরপর নতুন হয়। "নতুন করে হিসাব" চাপলে এখনই হয় (ডাটাবেসের কিছু পড়া খরচ হয়)।' }),
    ];
  };
});
