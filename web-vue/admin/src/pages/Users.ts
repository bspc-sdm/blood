// ব্যবহারকারী: খোঁজা, একজনের সব তথ্য ও ব্যবস্থা, অ্যাপের ভাগ ধরে পূর্ণ দৃশ্য (শুধু দেখা)
import { defineComponent, onMounted, ref, shallowRef, type PropType } from 'vue';
import { c, h, icon, type Kid } from '../h';
import { actionBtn, definePage, hold, listingChip, logTable, reasonBox, statusActions, table, userLink } from '../kit';
import { act, afterChange, go, setTitle } from '../state';
import { L, ago, bn, errMsg, fmtDT, fmtDate, type J } from '../util';

export const Users = definePage('PageUsers', async (r, { alive }) => {
  setTitle('ব্যবহারকারী', 'খুঁজুন ও ব্যবস্থা নিন');
  const q0 = r.params.get('q') || '';
  const text = ref(q0);
  const box = shallowRef<Kid>(null);
  const input = ref<HTMLInputElement | null>(null);
  const painter = () => [
    h('div', { class: 'card pad' }, h('form', { class: 'field-row', onsubmit: (e: Event) => { e.preventDefault(); go(`#/users?q=${encodeURIComponent(text.value.trim())}`); } },
      h('input', { ref: input, type: 'search', value: text.value, placeholder: 'ইমেইল, ফোন নম্বর বা ইউজার আইডি', 'aria-label': 'ব্যবহারকারী খুঁজুন', oninput: (e: Event) => { text.value = (e.target as HTMLInputElement).value; } }),
      h('button', { class: 'btn primary', type: 'submit' }, icon('search'), 'খুঁজুন'))),
    h('div', { class: 'section-title' }),
    h('div', null, box.value),
  ];
  if (!q0) {
    // খালি হলে খোঁজার ঘরে ফোকাস
    setTimeout(() => input.value?.focus(), 0);
    return painter;
  }
  box.value = h('p', { class: 'note', text: 'খোঁজা হচ্ছে…' });
  (async () => {
    try {
      const res = await act('admin_user_find', { q: q0 });
      if (!alive()) return;
      box.value = table(res.items || [], [
        { label: 'নাম', main: true, cell: (u) => [u.name || '—', u.disabled ? [' ', h('span', { class: 'chip bad', text: 'অ্যাকাউন্ট বন্ধ' })] : null] },
        { label: 'ইমেইল', cell: (u) => u.email || '—' },
        { label: 'ফোন', cell: (u) => bn(u.phone) || '—' },
        { label: 'রক্তের গ্রুপ', cell: (u) => u.bloodGroup || '—' },
        { label: 'এলাকা', cell: (u) => u.district || '—' },
      ], null, (u) => go(`#/user/${encodeURIComponent(u.uid)}`));
    } catch (e) {
      box.value = h('p', { class: 'error', role: 'alert', text: errMsg(e) });
    }
  })();
  return painter;
});

const ctl = (title: string, desc: string | null, chip: Kid, actions: Kid) => h('div', { class: 'ctl' },
  h('div', { class: 'what' }, h('b', null, title, chip ? [' ', chip] : null), desc ? h('div', { class: 'note', text: desc }) : null),
  h('div', { class: 'actions' }, actions));

export const User = definePage('PageUser', async (r, { alive }) => {
  setTitle('ব্যবহারকারী', '');
  const res = await act('admin_user', { uid: r.arg });
  if (!alive()) return;
  const u = res.user;
  setTitle(u.name || u.email || 'ব্যবহারকারী', u.disabled ? 'অ্যাকাউন্ট বন্ধ' : '');
  const reload = async () => {
    await afterChange();
  };
  const uact = (op: string) => () => act('admin_user_action', { uid: u.uid, op });

  // বাসায় সেবা: শুধু ডাক্তার/নার্সের, অথবা আগের নিষেধাজ্ঞা থাকলে
  const homeCareCtl = () => {
    const blocked = u.blocks.homeVisitBanned || u.blocks.homeVisitBlocked;
    const pro = u.badge && ['doctor', 'nurse'].includes(u.badge.type);
    const ps = u.provider || {};
    // রিপোর্টে পুরো তালিকা বন্ধ থাকলে সেটা আগে (বাসায় সেবার ব্লক থাকলেও)
    if (ps.suspended) {
      return ctl('ডাক্তার/নার্সের তালিকা', ps.suspendReason === 'report' ? 'রিপোর্টের পর তালিকা (চেম্বার আর বাসায় সেবা) বন্ধ।' : 'অ্যাকাউন্ট বন্ধের সাথে তালিকাও বন্ধ।',
        h('span', { class: 'chip bad', text: 'বন্ধ' }),
        []);
    }
    if (!blocked && !pro) return null;
    if (blocked) {
      return ctl('বাসায় সেবা', u.blocks.homeVisitBlocked ? 'রিপোর্টের পর বন্ধ: তালিকায় দেখান না, নিজে আবার চালু করতে পারবেন না।' : 'আগের হোম ভিজিটে রোগী হিসেবে নিষেধাজ্ঞা ছিল (এখন অনুরোধ-ব্যবস্থা নেই)।',
        h('span', { class: 'chip bad', text: 'বন্ধ' }),
        [actionBtn('নিষেধাজ্ঞা তুলুন', 'tonal', uact('homevisit_unban'), 'নিষেধাজ্ঞা তোলা হয়েছে। ডাক্তার/নার্স অ্যাপ থেকে নিজে আবার চালু করবেন।', reload)]);
    }
    const p = u.provider || {};
    return ctl('বাসায় সেবা',
      p.homeVisit ? `তালিকায় আছেন। রোগীরা ফোন করেন ${bn(p.homePhone) || '—'} নম্বরে; অ্যাপ থেকে ফোন এসেছে ${bn(p.homeCalls || 0)} বার।` : 'চালু করেননি।',
      p.homeVisit ? h('span', { class: 'chip good', text: 'চালু' }) : null,
      p.homeVisit ? [actionBtn('বাসায় সেবা বন্ধ করুন', 'warn', uact('homecare_ban'), 'বাসায় সেবা বন্ধ করা হয়েছে', reload)] : []);
  };

  const controls = () => {
    const out: Kid[] = [
      ctl('অ্যাকাউন্ট', u.disabled ? 'বন্ধ: লগইন করতে পারবেন না।' : 'চালু।',
        h('span', { class: `chip ${u.disabled ? 'bad' : 'good'}`, text: u.disabled ? 'বন্ধ' : 'চালু' }),
        u.isOwner ? [h('span', { class: 'note', text: 'মালিকের অ্যাকাউন্ট' })]
          : u.disabled ? [actionBtn('অ্যাকাউন্ট খুলে দিন', 'tonal', uact('enable'), 'অ্যাকাউন্ট খুলে দেওয়া হয়েছে', reload)]
            : (() => {
              // কারণ ব্যবহারকারী লগইনের চেষ্টায় দেখেন
              const reason = hold(u, 'disable');
              return [reasonBox(reason), actionBtn('অ্যাকাউন্ট বন্ধ করুন', 'warn', () => act('admin_user_action', { uid: u.uid, op: 'disable', reason: reason.value.trim() }), 'অ্যাকাউন্ট বন্ধ করা হয়েছে', reload)];
            })()),
      homeCareCtl(),
    ];
    if (u.badge) {
      const run = (approve: boolean) => () => act('admin_badge_resolve', { uid: u.uid, approve });
      out.push(ctl(`ব্যাজ: ${L.badge[u.badge.type] || u.badge.type}`, `রেজি. নম্বর ${u.badge.regNo || '—'}${u.badge.workplace ? `, ${u.badge.workplace}` : ''}`,
        listingChip(u.badge.status),
        u.badge.status === 'approved' ? [actionBtn('ব্যাজ বাতিল', 'warn', run(false), 'ব্যাজ বাতিল করা হয়েছে', reload)]
          // অনুমোদন শুধু যাচাই পাতা থেকে (ছবি দেখে চেকলিস্ট টিক; সার্ভার টিক ছাড়া অনুমোদন নেয় না)
          : [h('a', { class: 'btn primary', href: `#/verify/badges:${encodeURIComponent(u.uid)}`, text: 'যাচাই করে অনুমোদন' }), u.badge.status === 'pending' ? actionBtn('বাতিল', 'warn', run(false), 'আবেদন বাতিল করা হয়েছে', reload) : null]));
    }
    if (u.pharmacy) {
      out.push(ctl(`ফার্মেসি: ${u.pharmacy.name}`, [bn(u.pharmacy.phone), u.pharmacy.district].filter(Boolean).join(', '), listingChip(u.pharmacy.status),
        statusActions('admin_pharmacy_resolve', u.pharmacy.id, u.pharmacy.status, 'ফার্মেসি', u.pharmacy)));
    }
    (u.bloodBanks || []).forEach((b: J) => out.push(ctl(`ব্লাড ব্যাংক: ${b.name}`, [bn(b.phone), b.district].filter(Boolean).join(', '), listingChip(b.status),
      statusActions('admin_bloodbank_resolve', b.id, b.status, 'ব্লাড ব্যাংক', b))));
    (u.ambulances || []).forEach((a: J) => out.push(ctl(`অ্যাম্বুলেন্স: ${a.name}`, [a.vehicleNo, bn(a.phone)].filter(Boolean).join(', '), listingChip(a.status),
      statusActions('admin_ambulance_resolve', a.id, a.status, 'অ্যাম্বুলেন্স', a))));
    if (u.provider) {
      const pv = u.provider;
      // সাময়িক বন্ধ / আবার চালু / চেম্বার সরানো (নিজে লেখা জায়গা ভুল বা ভুয়া হলে)
      const pActs = pv.suspended
        ? (pv.suspendReason === 'account' ? [] : [actionBtn('আবার চালু', 'tonal', uact('provider_unsuspend'), 'তালিকা আবার চালু হয়েছে', reload)])
        : [
          actionBtn('সাময়িক বন্ধ', 'warn', uact('provider_suspend'), 'তালিকা সাময়িক বন্ধ (চেম্বার আর বাসায় সেবা)', reload),
          ...(pv.chambers ? [actionBtn('চেম্বার সরান', 'warn', uact('provider_chambers_remove'), 'চেম্বার সরানো হয়েছে, সামনের অ্যাপয়েন্টমেন্ট বাতিল', reload)] : []),
        ];
      out.push(ctl('পেশাজীবীর তালিকা', `${pv.suspended ? 'বন্ধ' : pv.listed ? 'তালিকায় দেখা যায়' : 'তালিকায় নেই'}${pv.chambers ? `, ${bn(pv.chambers)}টা চেম্বার` : ', চেম্বার নেই'}${pv.homeVisit ? ', বাসায় যান' : ''}`,
        pv.suspended ? h('span', { class: 'chip bad', text: 'বন্ধ' }) : null, pActs));
    }
    return out;
  };

  const facts: [string, string][] = [
    ['ইমেইল', u.email || '—'], ['ফোন', `${bn(u.phone) || '—'}${u.phoneVerified ? ' (যাচাই করা)' : ''}`],
    ['রক্তের গ্রুপ', u.bloodGroup || '—'], ['এলাকা', u.district || '—'],
    ['শেষ রক্তদান', u.lastDonationMillis ? fmtDate(u.lastDonationMillis) : 'দেননি / জানা নেই'],
    ['যোগ দিয়েছেন', fmtDate(u.createdAt)], ['শেষ সক্রিয়', ago(u.lastSeenAt)],
    ['তার বিরুদ্ধে রিপোর্ট', bn(u.activity.reportsAgainst)], ['ইউজার আইডি', u.uid],
  ];
  const actTable = (title: string, items: J[], cols: Parameters<typeof table>[1]) => [h('h2', { class: 'section-title', text: `${title} (${bn(items.length)})` }), table(items, cols)];

  return () => [
    h('div', { class: 'card pad' },
      h('div', { class: 'profile' },
        h('div', null, h('h2', { text: u.name || '—' }),
          h('div', { class: 'chips' }, u.isOwner ? h('span', { class: 'chip pri', text: 'মালিক' }) : u.isAdmin ? h('span', { class: 'chip pri', text: 'অ্যাডমিন' }) : null,
            u.badge && u.badge.status === 'approved' ? h('span', { class: 'chip good', text: L.badge[u.badge.type] }) : null)),
        h('div', null, h('a', { href: `#/users?q=${encodeURIComponent(u.email || u.uid)}`, text: 'খোঁজায় ফিরুন' }))),
      h('div', { class: 'facts' }, facts.map(([k, v]) => h('div', null, h('span', { text: k }), v)))),
    h('h2', { class: 'section-title', text: 'ব্যবস্থা' }),
    h('div', { class: 'card pad' }, controls()),
    (u.donationDateLog || []).length ? actTable('শেষ রক্তদানের তারিখ বদল', u.donationDateLog, [
      { label: 'কখন', main: true, cell: (x) => fmtDT(x.at) },
      { label: 'আগে', cell: (x) => (x.from ? fmtDate(x.from) : 'কখনো দেননি') },
      { label: 'পরে', cell: (x) => (x.to ? fmtDate(x.to) : 'কখনো দেননি') },
      { label: 'সতর্কতা', cell: (x) => (x.risk === 'backward' ? h('span', { class: 'chip bad', text: 'পেছানো' }) : x.risk === 'short_gap' ? h('span', { class: 'chip bad', text: '১২০ দিনের কম ফাঁক' }) : '—') },
    ]) : null,
    h('h2', { class: 'section-title', text: 'অ্যাপে যা যা করেছেন' }),
    c(UserTabs, { u }),
  ];
});

// =========================================================
// ব্যবহারকারীর পূর্ণ দৃশ্য: অ্যাপের ভাগ ধরে (শুধু দেখা)
// =========================================================
const RESP: Record<string, string> = { accepted: 'রাজি', donated: 'রক্ত দিয়েছেন', cancelled: 'বাতিল করেছেন', declined: 'বাদ পড়েছেন' };
const EMER: Record<string, string> = { notified: 'জানানো হয়েছিল', coming: 'আসছি বলেছেন', cancelled: 'আসতে পারেননি', donated: 'রক্ত দিয়েছেন', not_donated: 'দেননি' };
const CALLKIND: Record<string, string> = { ambulance: 'অ্যাম্বুলেন্স', pharmacy: 'ফার্মেসি', bloodbank: 'ব্লাড ব্যাংক', homecare: 'বাসায় সেবা' };
const USER_TABS: [string, string][] = [['blood', 'রক্ত'], ['community', 'আলোচনা'], ['services', 'সেবা'], ['care', 'ডাক্তার ও অ্যাপয়েন্টমেন্ট'],
  ['inbox', 'নোটিফিকেশন'], ['support', 'আপিল'], ['admin', 'অ্যাডমিন ব্যবস্থা'], ['timeline', 'সময়ক্রম']];

const sub = (title: string, n?: number | null) => h('h3', { class: 'sub-title', text: n == null ? title : `${title} (${bn(n)})` });
const note = (t: string) => h('p', { class: 'note', text: t });
const idxNote = (v: string | null | undefined) => (v === 'index' ? note('এই অংশ দেখাতে Firestore-এ একটা সূচি (index) চালু করতে হবে — Lifolo টিমকে জানান।') : v ? note(v) : null);
const factsOf = (rows: [string, string][]) => h('div', { class: 'facts' }, rows.map(([k, v]) => h('div', null, h('span', { text: k }), v)));

const render: Record<string, (d: J) => Kid> = {
  blood: (d) => [
    d.profile ? factsOf([
      ['রক্তের গ্রুপ', d.profile.bloodGroup === 'unknown' ? 'জানা নেই' : d.profile.bloodGroup || '—'],
      ['শেষ রক্তদান', d.profile.lastDonationMillis ? fmtDate(d.profile.lastDonationMillis) : '—'],
      ['এলাকা', d.profile.placeName || '—'], ['নোটিফিকেশন', d.profile.notify ? 'চালু' : 'বন্ধ'],
      ['ফোন (ডিভাইস)', `${bn(d.profile.devices)}টা`], ['এখন রাজি আছেন', `${bn(d.profile.openCommitments)}টা রিকোয়েস্টে`],
    ]) : null,
    sub('তাঁর দেওয়া রিকোয়েস্ট', d.requests.length),
    table(d.requests, [
      { label: 'কবে', main: true, cell: (x) => fmtDT(x.at) },
      { label: 'রক্ত', cell: (x) => `${x.group || '—'} · ${bn(x.bags)} ব্যাগ` },
      { label: 'হাসপাতাল', cell: (x) => [x.hospital || '—', x.place ? h('div', { class: 'small', text: x.place }) : null] },
      { label: 'সাড়া', cell: (x) => `রাজি ${bn(x.accepted)} · নিশ্চিত ${bn(x.confirmed)}` },
      { label: 'অবস্থা', cell: (x) => [L.request[x.status] || x.status, x.closedBy ? h('div', { class: 'small', text: ({ requester: 'নিজে বন্ধ', not_needed: 'আর লাগবে না', expired: 'সময় শেষ' } as Record<string, string>)[x.closedBy] || x.closedBy }) : null] },
    ]),
    sub('যেসব রিকোয়েস্টে সাড়া দিয়েছেন', d.responses.length), idxNote(d.responsesNote),
    table(d.responses, [
      { label: 'কবে', main: true, cell: (x) => fmtDT(x.at) },
      { label: 'রিকোয়েস্ট', cell: (x) => [`${x.group || '—'} · ${x.hospital || '—'}`, x.requesterUid ? h('div', { class: 'small' }, userLink(x.requesterUid, 'রক্তপ্রার্থী')) : null] },
      { label: 'অবস্থা', cell: (x) => (RESP[x.status] || x.status) + (x.confirmed ? ' · নিশ্চিত' : '') },
    ]),
    sub('জরুরি আহ্বান', d.emergencies.length), idxNote(d.emergenciesNote),
    table(d.emergencies, [
      { label: 'কবে', main: true, cell: (x) => fmtDT(x.at) },
      { label: 'হাসপাতাল', cell: (x) => x.hospital || '—' },
      { label: 'সাড়া', cell: (x) => EMER[x.status] || x.status },
    ]),
    d.corrections.length ? [sub('রক্তদানের তারিখ সংশোধনের অনুরোধ', d.corrections.length), table(d.corrections, [
      { label: 'কবে', main: true, cell: (x) => fmtDT(x.at) },
      { label: 'চাওয়া তারিখ', cell: (x) => (x.requestedMillis ? fmtDate(x.requestedMillis) : 'কখনো দেননি') },
      { label: 'কারণ', cell: (x) => x.reason || '—' },
      { label: 'অবস্থা', cell: (x) => x.status },
    ])] : null,
  ],
  community: (d) => [
    sub('প্রশ্ন', d.questions.length),
    table(d.questions, [
      { label: 'কবে', main: true, cell: (x) => fmtDT(x.at) },
      { label: 'প্রশ্ন', cell: (x) => [h('div', { class: 'body', text: x.text }), h('div', { class: 'chips' },
        x.anonymous ? h('span', { class: 'chip', text: 'নাম লুকানো' }) : null, x.edited ? h('span', { class: 'chip', text: 'সম্পাদিত' }) : null)] },
      { label: 'উত্তর', cell: (x) => bn(x.answers) },
    ]),
    sub('উত্তর', d.answers.length), idxNote(d.answersNote),
    table(d.answers, [
      { label: 'কবে', main: true, cell: (x) => fmtDT(x.at) },
      { label: 'উত্তর', cell: (x) => h('div', { class: 'body', text: x.text }) },
    ]),
    sub('টিপস', d.tips.length),
    table(d.tips, [
      { label: 'কবে', main: true, cell: (x) => fmtDT(x.at) },
      { label: 'টিপস', cell: (x) => [h('b', { text: x.title }), h('div', { class: 'small', text: x.body })] },
    ]),
    sub('যা রিপোর্ট করেছেন', d.reportsMade.length),
    table(d.reportsMade, [
      { label: 'কবে', main: true, cell: (x) => fmtDT(x.at) },
      { label: 'কী', cell: (x) => L.report[x.kind] || x.kind },
      { label: 'কারণ', cell: (x) => x.reason || '—' },
      { label: 'অবস্থা', cell: (x) => ({ open: 'খোলা', removed: 'ব্যবস্থা নেওয়া হয়েছে', dismissed: 'রেখে দেওয়া' } as Record<string, string>)[x.status] || x.status },
    ]),
  ],
  services: (d) => {
    const lst = (title: string, items: J[], extra: (x: J) => string) => [sub(title, items.length), table(items, [
      { label: 'নাম', main: true, cell: (x) => [x.name || '—', x.verified ? h('div', { class: 'chips' }, h('span', { class: 'chip good', text: 'কাগজ যাচাই করা' })) : null] },
      { label: 'তথ্য', cell: (x) => [extra(x), h('div', { class: 'small', text: [bn(x.phone), x.district].filter(Boolean).join(' · ') })] },
      { label: 'অবস্থা', cell: (x) => [L.listing[x.status] || x.status, x.statusReason ? h('div', { class: 'small', text: `কারণ: ${x.statusReason}` }) : null] },
      { label: 'রেটিং', cell: (x) => (x.rating ? bn(x.rating) : '—') },
    ])];
    return [
      lst('তাঁর অ্যাম্বুলেন্স', d.ambulances, (x) => x.vehicleNo || ''),
      lst('তাঁর ফার্মেসি', d.pharmacy ? [d.pharmacy] : [], (x) => `লাইসেন্স ${x.licenseNo || '—'}`),
      lst('তাঁর ব্লাড ব্যাংক', d.bloodBanks, (x) => L.bankKind[x.kind] || x.kind || ''),
      sub('যেখানে ফোন করেছেন', d.calls.length),
      table(d.calls, [
        { label: 'শেষ ফোন', main: true, cell: (x) => fmtDT(x.at) },
        { label: 'কোথায়', cell: (x) => [x.name, h('div', { class: 'small', text: CALLKIND[x.kind] || x.kind })] },
        { label: 'রেটিং', cell: (x) => (x.rating ? [`${bn(x.rating)} ★`, x.comment ? h('div', { class: 'small', text: x.comment }) : null] : '—') },
      ]),
      sub('ওষুধ খোঁজা', d.medFinds.length),
      table(d.medFinds, [
        { label: 'কবে', main: true, cell: (x) => fmtDT(x.at) },
        { label: 'ওষুধ', cell: (x) => [x.drug, h('div', { class: 'small', text: x.district })] },
        { label: 'উত্তর', cell: (x) => `${bn(x.asked)}টা দোকানে জিজ্ঞেস, ${bn(x.have)}টায় আছে` },
      ]),
    ];
  },
  care: (d) => [
    d.provider ? [sub('ডাক্তার/নার্সের তালিকা'), factsOf([
      ['পেশা', L.badge[d.provider.type] || d.provider.type || '—'], ['তালিকায়', d.provider.suspended ? 'বন্ধ' : d.provider.listed ? 'দেখা যায়' : 'নেই'],
      ['বাসায় সেবা', d.provider.homeVisit ? `চালু · ${bn(d.provider.homePhone)}` : 'বন্ধ'], ['বাসায় সেবায় ফোন এসেছে', `${bn(d.homeCallsReceived)} জনের`],
    ]),
    table(d.provider.chambers, [
      { label: 'চেম্বার', main: true, cell: (x) => x.hospital || '—' },
      { label: 'বার', cell: (x) => (x.days || []).map((n: number) => ['রবি', 'সোম', 'মঙ্গল', 'বুধ', 'বৃহস্পতি', 'শুক্র', 'শনি'][n]).join(', ') || '—' },
      { label: 'সময়', cell: (x) => (x.start ? `${bn(x.start)}–${bn(x.end)}` : '—') },
      { label: 'অ্যাপয়েন্টমেন্ট', cell: (x) => (x.appointments ? 'নেন' : 'নেন না') },
    ])] : note('ডাক্তার/নার্সের তালিকায় নেই।'),
    sub('অ্যাপয়েন্টমেন্ট', d.appointments.length),
    table(d.appointments, [
      { label: 'অনুরোধ', main: true, cell: (x) => fmtDT(x.at) },
      { label: 'ভূমিকা', cell: (x) => (x.role === 'provider' ? 'ডাক্তার/নার্স হিসেবে' : 'রোগী হিসেবে') },
      { label: 'কার সাথে', cell: (x) => [x.other || '—', h('div', { class: 'small', text: x.chamber })] },
      { label: 'দিন', cell: (x) => [fmtDate(x.date), x.serial ? h('div', { class: 'small', text: `সিরিয়াল ${bn(x.serial)}` }) : null] },
      { label: 'অবস্থা', cell: (x) => L.appt[x.status] || x.status },
    ]),
  ],
  inbox: (d) => [sub('গত ২১ দিনের নোটিফিকেশন', d.items.length), table(d.items, [
    { label: 'কবে', main: true, cell: (x) => fmtDT(x.at) },
    { label: 'নোটিফিকেশন', cell: (x) => [h('b', { text: x.title }), x.body ? h('div', { class: 'small', text: x.body }) : null] },
  ])],
  support: (d) => [sub('আপিল ও সমস্যা', d.items.length), table(d.items, [
    { label: 'শেষ হালনাগাদ', main: true, cell: (x) => fmtDT(x.at) },
    { label: 'বিষয়', cell: (x) => [h('a', { href: `#/ticket/${encodeURIComponent(x.id)}`, text: L.category[x.category] || x.category }), h('div', { class: 'small', text: x.subject })] },
    { label: 'অবস্থা', cell: (x) => L.ticket[x.status] || x.status },
  ])],
  admin: (d) => {
    // শুধু দেখা (পাতা খোলা) আলাদা, আসল ব্যবস্থা আগে
    const acts = d.items.filter((x: J) => x.action !== 'admin_user_section');
    const views = d.items.filter((x: J) => x.action === 'admin_user_section');
    return [sub('এই ব্যবহারকারীর ওপর অ্যাডমিনদের ব্যবস্থা', acts.length), logTable(acts),
      sub('কে কবে এই পাতা দেখেছেন', views.length), logTable(views)];
  },
};

const UserTabs = defineComponent({
  name: 'UserTabs',
  props: { u: { type: Object as PropType<J>, required: true } },
  setup(p) {
    const cache: Record<string, Promise<J> | undefined> = {};
    const load = (sec: string) => (cache[sec] ??= act('admin_user_section', { uid: p.u.uid, section: sec }));
    const current = ref('');
    const content = shallowRef<Kid>(null);

    // সব ভাগ এক তালিকায়, নতুন আগে
    async function timeline(): Promise<Kid> {
      const secs = ['blood', 'community', 'services', 'care', 'support'];
      const [b, cm, sv, ca, sp] = await Promise.all(secs.map(load));
      const ev = [
        ...b.requests.map((x: J) => ({ at: x.at, area: 'রক্ত', text: `রিকোয়েস্ট দিয়েছেন: ${x.group} · ${x.hospital}` })),
        ...b.responses.map((x: J) => ({ at: x.at, area: 'রক্ত', text: `সাড়া (${RESP[x.status] || x.status}): ${x.group} · ${x.hospital}` })),
        ...b.emergencies.map((x: J) => ({ at: x.at, area: 'জরুরি', text: `${EMER[x.status] || x.status}: ${x.hospital}` })),
        ...cm.questions.map((x: J) => ({ at: x.at, area: 'আলোচনা', text: `প্রশ্ন: ${x.text.slice(0, 80)}` })),
        ...cm.answers.map((x: J) => ({ at: x.at, area: 'আলোচনা', text: `উত্তর: ${x.text.slice(0, 80)}` })),
        ...cm.tips.map((x: J) => ({ at: x.at, area: 'আলোচনা', text: `টিপস: ${x.title}` })),
        ...cm.reportsMade.map((x: J) => ({ at: x.at, area: 'রিপোর্ট', text: `রিপোর্ট করেছেন: ${L.report[x.kind] || x.kind}` })),
        ...sv.calls.map((x: J) => ({ at: x.at, area: 'সেবা', text: `ফোন করেছেন: ${x.name} (${CALLKIND[x.kind] || x.kind})${x.rating ? ` · ${x.rating}★` : ''}` })),
        ...sv.medFinds.map((x: J) => ({ at: x.at, area: 'সেবা', text: `ওষুধ খুঁজেছেন: ${x.drug}` })),
        ...ca.appointments.map((x: J) => ({ at: x.at, area: 'ডাক্তার', text: `অ্যাপয়েন্টমেন্ট (${x.role === 'provider' ? 'ডাক্তার হিসেবে' : 'রোগী হিসেবে'}): ${x.other}` })),
        ...sp.items.map((x: J) => ({ at: x.at, area: 'আপিল', text: L.category[x.category] || x.category })),
      ].filter((e) => e.at).sort((a, z) => z.at - a.at).slice(0, 200);
      return [sub('সব কাজ, নতুন আগে', ev.length), idxNote(b.responsesNote || cm.answersNote), table(ev, [
        { label: 'কবে', main: true, cell: (x) => [fmtDT(x.at), h('div', { class: 'small', text: ago(x.at) })] },
        { label: 'ভাগ', cell: (x) => h('span', { class: 'chip', text: x.area }) },
        { label: 'কী', cell: (x) => x.text },
      ])];
    }

    async function show(sec: string) {
      current.value = sec;
      content.value = h('p', { class: 'note', text: 'লোড হচ্ছে…' });
      try {
        const nodes = sec === 'timeline' ? await timeline() : render[sec](await load(sec));
        if (current.value === sec) content.value = nodes;
      } catch (e) {
        delete cache[sec];
        if (current.value === sec) content.value = h('p', { class: 'error', role: 'alert', text: errMsg(e) });
      }
    }
    onMounted(() => show('blood'));

    return () => h('div', null,
      h('div', { class: 'seg tabs-wrap', role: 'tablist' }, USER_TABS.map(([sec, label]) =>
        h('a', { href: '#', 'data-sec': sec, class: current.value === sec ? 'on' : null, text: label, onclick: (e: Event) => { e.preventDefault(); show(sec); } }))),
      h('div', { class: 'card pad' }, h('div', { class: 'user-tab' }, content.value)));
  },
});
