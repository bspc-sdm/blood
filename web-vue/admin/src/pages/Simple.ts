// ছোট পাতা: হাসপাতাল যোগের অনুরোধ, রিপোর্ট, তারিখ সংশোধন, কাজের ইতিহাস, অ্যাডমিন
import { ref } from 'vue';
import { c, h } from '../h';
import { ActBtn, actionBtn, definePage, hold, logTable, reasonBox, table, userLink } from '../kit';
import { S, act, afterChange, itemsOf, post, setTitle } from '../state';
import { EMAIL_RE, L, bn, fmtDate, type J } from '../util';

// =========================================================
// হাসপাতাল যোগের অনুরোধ
// =========================================================
export const Hospitals = definePage('PageHospitals', () => {
  const items = itemsOf('hospitalRequests');
  setTitle('হাসপাতাল যোগের অনুরোধ', `${bn(items.length)}টা অপেক্ষায়`);
  const run = (id: string, op: string, targetId?: string) => () => act('admin_hospital_resolve', { id, op, ...(targetId ? { targetId } : {}) });
  return () => [
    h('p', { class: 'note', text: 'ব্যবহারকারীরা যে হাসপাতাল তালিকায় পাননি, সেগুলো যোগ করতে চেয়েছেন। আগে থেকে একই হাসপাতাল থাকলে "মেলান" চাপুন।' }),
    table(items, [
      { label: 'হাসপাতাল', main: true, cell: (x) => [x.nameEn || x.nameBn || '—', x.nameBn && x.nameEn ? h('div', { class: 'small', text: x.nameBn }) : null,
        x.count > 1 ? h('div', { class: 'chips' }, h('span', { class: 'chip pri', text: `${bn(x.count)} জন চেয়েছেন` })) : null] },
      { label: 'ধরন', cell: (x) => L.hospitalType[x.type] || x.type || '—' },
      { label: 'এলাকা', cell: (x) => [x.areaLabel || x.district || '—', x.address ? h('div', { class: 'small', text: x.address }) : null, x.phone ? h('div', { class: 'small', text: bn(x.phone) }) : null] },
      { label: 'একই রকম নাম', cell: (x) => ((x.possibleDuplicates || []).length
        ? (x.possibleDuplicates || []).map((d: J) => h('div', { class: 'small' }, [d.nameBn || d.nameEn, d.areaLabel].filter(Boolean).join(', '), ' ',
          actionBtn('মেলান', 'quiet', run(x.id, 'merge', d.id), 'আগের হাসপাতালের সাথে মেলানো হয়েছে', afterChange)))
        : '—') },
    ], (x) => [actionBtn('অনুমোদন', 'primary', run(x.id, 'approve'), 'হাসপাতাল তালিকায় যোগ হয়েছে', afterChange), actionBtn('বাতিল', 'warn', run(x.id, 'reject'), 'অনুরোধ বাতিল করা হয়েছে', afterChange)]),
  ];
});

// =========================================================
// রিপোর্ট
// =========================================================
export const Reports = definePage('PageReports', () => {
  const items = itemsOf('reports');
  setTitle('রিপোর্ট', `${bn(items.length)}টা খোলা`);
  return () => table(items, [
    { label: 'কী', main: true, cell: (x) => [x.kind === 'homevisit' ? (x.reportedRole === 'patient' ? 'হোম ভিজিট (আগের): রোগী' : 'হোম ভিজিট (আগের): নার্স') : L.report[x.kind] || x.kind,
      h('div', { class: 'chips' }, h('span', { class: 'chip bad', text: `${bn(x.count)}টি রিপোর্ট` }))] },
    { label: 'কার', cell: (x) => [x.authorName || '—', x.reportedUid || x.kind === 'homecare' || x.kind === 'provider' ? h('div', { class: 'small' }, userLink(x.reportedUid || x.targetPath.split('/')[1])) : null] },
    { label: 'লেখা', cell: (x) => [h('div', { class: 'body', text: x.text || '' }),
      x.snapshot ? h('div', { class: 'small' }, h('span', { class: 'chip bad', text: 'রিপোর্টের পর বদলেছে' }), ' রিপোর্টের সময়ের লেখা: ', x.snapshot) : null,
      ['question', 'answer', 'tip'].includes(x.kind) && (x.reasons || []).length ? h('div', { class: 'small', text: `কারণ: ${x.reasons.join('; ')}` }) : null] },
  ], (x) => {
    const body = { targetPath: x.targetPath, kind: x.kind, ...(x.reportedUid ? { reportedUid: x.reportedUid } : {}) };
    const listing = x.kind === 'ambulance' || x.kind === 'pharmacy' || x.kind === 'bloodbank';
    const reason = listing ? hold(x) : null;
    const removeLabel = x.kind === 'homevisit' || x.kind === 'homecare' ? 'বাসায় সেবা বন্ধ' : x.kind === 'provider' ? 'তালিকা বন্ধ' : x.kind === 'ambulance' || x.kind === 'pharmacy' || x.kind === 'bloodbank' ? 'বন্ধ করুন' : 'পোস্ট মুছুন';
    return [
      reason ? reasonBox(reason) : null,
      actionBtn(removeLabel, 'warn', () => act('admin_report_resolve', { ...body, remove: true, ...(reason && reason.value.trim() ? { reason: reason.value.trim() } : {}) }), 'ব্যবস্থা নেওয়া হয়েছে', afterChange),
      actionBtn('রেখে দিন', 'quiet', () => act('admin_report_resolve', { ...body, remove: false }), 'রেখে দেওয়া হয়েছে', afterChange),
    ];
  });
});

// =========================================================
// তারিখ সংশোধন
// =========================================================
export const Corrections = definePage('PageCorrections', () => {
  const items = itemsOf('corrections');
  setTitle('রক্তদানের তারিখ সংশোধন', `${bn(items.length)}টা অপেক্ষায়`);
  const run = (id: string, approve: boolean) => () => post('/api/admin/resolve', { id, approve });
  return () => table(items, [
    { label: 'নাম', main: true, cell: (x) => [x.name || '—', h('div', { class: 'small' }, userLink(x.uid))] },
    { label: 'এখনকার তারিখ', cell: (x) => fmtDate(x.currentMillis) },
    { label: 'চাওয়া তারিখ', cell: (x) => (x.requestedMillis ? fmtDate(x.requestedMillis) : 'কখনো দেননি') },
    { label: 'কারণ', cell: (x) => h('div', { class: 'body', text: x.reason || '—' }) },
  ], (x) => [actionBtn('অনুমোদন', 'primary', run(x.id, true), 'তারিখ সংশোধন করা হয়েছে', afterChange), actionBtn('বাতিল', 'warn', run(x.id, false), 'অনুরোধ বাতিল করা হয়েছে', afterChange)]);
});

// =========================================================
// কাজের ইতিহাস
// =========================================================
export const Log = definePage('PageLog', async (_r, { alive }) => {
  setTitle('কাজের ইতিহাস', 'কোন অ্যাডমিন কবে কী করেছেন');
  const res = await act('admin_log');
  if (!alive()) return;
  const items = ref<J[]>(res.items || []);
  const more = ref(items.value.length >= 100);
  const loadMore = async () => {
    const last = items.value[items.value.length - 1];
    const r2 = await act('admin_log', { before: last ? last.at : undefined });
    items.value = items.value.concat(r2.items || []);
    if (!(r2.items || []).length) more.value = false;
  };
  return () => [
    h('div', null, logTable(items.value)),
    more.value ? h('div', { class: 'toolbar' }, c(ActBtn, { label: 'আরও আগের', kind: 'quiet', run: loadMore, resetAfter: true })) : null,
  ];
});

// =========================================================
// অ্যাডমিন (শুধু মালিক)
// =========================================================
export const Admins = definePage('PageAdmins', () => {
  setTitle('অ্যাডমিন', 'যোগ বা বাদ শুধু মালিক');
  const a = S.overview && S.overview.admins;
  if (!a) return () => h('p', { class: 'note', text: 'এই পাতা শুধু মালিক দেখতে পারেন।' });
  const email = ref('');
  const rows = [...(a.owners || []).map((e: string) => ({ email: e, owner: true })), ...(a.items || [])];
  return () => [
    h('p', { class: 'note', text: 'অ্যাডমিনরা সব আবেদন, আপিল আর রিপোর্ট দেখতে ও ব্যবস্থা নিতে পারেন। তাদের এই ইমেইল দিয়েই লগইন করতে হবে।' }),
    h('div', { class: 'section-title' }),
    table(rows, [
      { label: 'ইমেইল', main: true, cell: (x) => [x.email, x.owner ? [' ', h('span', { class: 'chip pri', text: 'মালিক' })] : null] },
    ], (x) => (x.owner ? [] : [actionBtn('বাদ দিন', 'warn', () => act('admin_remove', { email: x.email }), 'অ্যাডমিন থেকে বাদ দেওয়া হয়েছে', afterChange)])),
    h('h2', { class: 'section-title', text: 'নতুন অ্যাডমিন' }),
    h('div', { class: 'card pad' }, h('div', { class: 'field-row' },
      h('input', { type: 'email', placeholder: 'নতুন অ্যাডমিনের ইমেইল', 'aria-label': 'নতুন অ্যাডমিনের ইমেইল', maxlength: 200, value: email.value, oninput: (e: Event) => { email.value = (e.target as HTMLInputElement).value; } }),
      actionBtn('যোগ করুন', 'primary', async () => {
        const v = email.value.trim().toLowerCase();
        if (!EMAIL_RE.test(v)) throw new Error('সঠিক ইমেইল দিন');
        await act('admin_add', { email: v });
      }, 'অ্যাডমিন যোগ হয়েছে', afterChange))),
  ];
});
