// অনুমোদনের তালিকা: ব্যাজ, ফার্মেসি, প্যাথলজি, অ্যাম্বুলেন্স, ব্লাড ব্যাংক
import { defineComponent, nextTick, reactive, ref, type PropType } from 'vue';
import { c, h, type Kid } from '../h';
import { actionBtn, closedDaysNote, definePage, hold, hoursText, reasonBox, reviewCell, statusActions, table, toolbar, userLink, type Col } from '../kit';
import { S, act, afterChange, getList, route, setTitle } from '../state';
import { L, WEEKDAYS, bn, fmtDate, matches, type J } from '../util';

export interface ListingDef {
  title: string;
  action: string;
  tabs: [string, string][];
  search: string;
  text: (x: J) => unknown[];
  cols: Col[];
  note?: string;
  id: (x: J) => string;
  owner: (x: J) => string;
  actions: (x: J, st: string) => Kid;
  extra?: () => Kid;
}

const bmdc = (b: J) => (b.type === 'doctor' ? h('a', { href: 'https://www.bmdc.org.bd/', target: '_blank', rel: 'noopener noreferrer', text: 'BMDC-তে মেলান' })
  : b.type === 'nurse' ? h('a', { href: 'https://www.bnmc.gov.bd/', target: '_blank', rel: 'noopener noreferrer', text: 'BNMC-তে মেলান' })
    : 'সনদ দেখে নিন');

export const LISTINGS: Record<string, ListingDef> = {
  badges: {
    title: 'ব্যাজ',
    action: 'admin_badges',
    tabs: [['pending', 'অপেক্ষায়'], ['approved', 'অনুমোদিত'], ['rejected', 'বাতিল']],
    search: 'নাম, ইমেইল, রেজি. নম্বর',
    text: (b) => [b.name, b.email, b.regNo, b.workplace, L.badge[b.type], ...(b.change ? [b.change.name, b.change.regNo, b.change.workplace] : [])],
    cols: [
      // অনুমোদিত ব্যাজের সংশোধনের অনুরোধ: নতুন তথ্য বড় করে, আগেরটা নিচে ছোট করে
      { label: 'নাম', main: true, cell: (b) => (b.change
        ? [b.change.name || '—', ' ', h('span', { class: 'chip bad', text: 'সংশোধন' }), ' ', h('span', { class: 'chip pri', text: L.badge[b.change.type] || b.change.type }),
          b.change.name !== b.name ? h('div', { class: 'small', text: `আগে: ${b.name || '—'}` }) : null]
        : [b.name || '—', ' ', h('span', { class: 'chip pri', text: L.badge[b.type] || b.type })]) },
      { label: 'ইমেইল', cell: (b) => b.email || '—' },
      { label: 'রেজি. নম্বর', cell: (b) => [(b.change ? b.change.regNo : b.regNo) || '—',
        b.change && b.change.regNo !== b.regNo ? h('div', { class: 'small', text: `আগে: ${b.regNo || '—'}` }) : null, h('div', { class: 'small' }, bmdc(b))] },
      { label: 'কর্মস্থল', cell: (b) => [(b.change ? b.change.workplace : b.workplace) || '—',
        b.change && b.change.workplace !== b.workplace ? h('div', { class: 'small', text: `আগে: ${b.workplace || '—'}` }) : null] },
      { label: 'তারিখ', cell: (b) => fmtDate(b.change ? b.change.at : b.reviewedAt || b.createdAt) },
      { label: 'কাগজ / যাচাই', cell: (b) => reviewCell(b) },
    ],
    id: (b) => b.uid + (b.change ? ':change' : ''),
    owner: (b) => b.uid,
    actions: (b, st) => {
      const reason = hold(b);
      const run = (approve: boolean) => () => act('admin_badge_resolve', { uid: b.uid, approve, reason: reason.value.trim() });
      // অনুমোদন শুধু যাচাইয়ের পাতা থেকে (কাগজ দেখে সব টিক দিয়ে)
      const verifyBtn = (label: string) => h('a', { class: 'btn primary', href: `#/verify/badges:${encodeURIComponent(b.uid + (b.change ? ':change' : ''))}`, text: label });
      if (st === 'pending' && b.change) {
        const fix = (approve: boolean) => () => act('admin_badge_resolve', { uid: b.uid, approve, correction: true });
        return [verifyBtn('যাচাই করে মঞ্জুর'), actionBtn('সংশোধন বাতিল', 'warn', fix(false), 'অনুরোধ বাতিল, ব্যাজ আগের মতো', afterChange)];
      }
      if (st === 'pending') return [reasonBox(reason), verifyBtn('যাচাই করে অনুমোদন'), actionBtn('বাতিল', 'warn', run(false), 'আবেদন বাতিল করা হয়েছে', afterChange)];
      if (st === 'approved') return [reasonBox(reason), actionBtn('ব্যাজ বাতিল', 'warn', run(false), 'ব্যাজ বাতিল করা হয়েছে, তালিকা থেকেও সরেছে', afterChange)];
      return [verifyBtn('যাচাই করে অনুমোদন')];
    },
  },
  pharmacies: {
    title: 'ফার্মেসি',
    action: 'admin_pharmacies',
    tabs: [['pending', 'অপেক্ষায়'], ['approved', 'অনুমোদিত'], ['suspended', 'বন্ধ'], ['rejected', 'বাতিল']],
    search: 'নাম, ফোন, লাইসেন্স, এলাকা',
    text: (p) => [p.name, p.phone, p.licenseNo, p.areaLabel, p.district, p.address],
    cols: [
      { label: 'নাম', main: true, cell: (p) => [p.name || '—', p.rating ? h('div', { class: 'small', text: `রেটিং ${bn(p.rating)} (${bn(p.ratingCount)})` }) : null] },
      { label: 'লাইসেন্স', cell: (p) => p.licenseNo || '—' },
      { label: 'ফোন', cell: (p) => bn(p.phone) },
      { label: 'এলাকা', cell: (p) => [p.areaLabel || p.district || '—', p.address ? h('div', { class: 'small', text: p.address }) : null] },
      { label: 'সময়', cell: (p) => [hoursText(p), closedDaysNote(p)] },
      { label: 'তারিখ', cell: (p) => fmtDate(p.reviewedAt || p.createdAt) },
      { label: 'বদল / নোট', cell: (p) => reviewCell(p) },
    ],
    note: 'অনুমোদনের আগে ঔষধ প্রশাসন অধিদপ্তরে (DGDA) লাইসেন্স যাচাই করুন।',
    id: (p) => p.id,
    owner: (p) => p.id,
    actions: (p, st) => statusActions('admin_pharmacy_resolve', p.id, st, 'ফার্মেসি', p),
  },
  labs: {
    title: 'প্যাথলজি',
    action: 'admin_labs',
    tabs: [['pending', 'অপেক্ষায়'], ['approved', 'অনুমোদিত'], ['suspended', 'বন্ধ'], ['rejected', 'বাতিল']],
    search: 'নাম, ফোন, লাইসেন্স, এলাকা',
    text: (p) => [p.name, p.phone, p.licenseNo, p.areaLabel, p.district, p.address],
    cols: [
      { label: 'নাম', main: true, cell: (p) => [p.name || '—', h('div', { class: 'small', text: p.facilityId ? 'স্বাস্থ্য অধিদপ্তরের তালিকা থেকে' : 'নিজে লেখা' })] },
      { label: 'লাইসেন্স', cell: (p) => p.licenseNo || '—' },
      { label: 'ফোন', cell: (p) => bn(p.phone) },
      { label: 'এলাকা', cell: (p) => [p.areaLabel || p.district || '—', p.address ? h('div', { class: 'small', text: p.address }) : null] },
      { label: 'সময়', cell: (p) => [p.hours || '—', p.homeCollection ? h('div', { class: 'small', text: 'বাসা থেকে নমুনা নেয়' }) : null] },
      { label: 'তারিখ', cell: (p) => fmtDate(p.reviewedAt || p.createdAt) },
      { label: 'বদল / নোট', cell: (p) => reviewCell(p) },
    ],
    note: 'অনুমোদনের আগে স্বাস্থ্য অধিদপ্তরের (DGHS) ক্লিনিক/ডায়াগনস্টিক লাইসেন্স যাচাই করুন।',
    id: (p) => p.id,
    owner: (p) => p.id,
    actions: (p, st) => statusActions('admin_lab_resolve', p.id, st, 'প্যাথলজি', p),
  },
  ambulances: {
    title: 'অ্যাম্বুলেন্স',
    action: 'admin_ambulances',
    tabs: [['pending', 'অপেক্ষায়'], ['approved', 'অনুমোদিত'], ['suspended', 'বন্ধ'], ['rejected', 'বাতিল']],
    search: 'নাম, ফোন, গাড়ির নম্বর, এলাকা',
    text: (a) => [a.name, a.phone, a.vehicleNo, a.areaLabel, a.district, a.area],
    cols: [
      { label: 'নাম', main: true, cell: (a) => [a.name || '—', h('div', { class: 'chips' }, (a.types || []).map((t: string) => h('span', { class: 'chip', text: L.amb[t] || t })))] },
      { label: 'গাড়ির নম্বর', cell: (a) => a.vehicleNo || '—' },
      { label: 'ফোন', cell: (a) => bn(a.phone) },
      { label: 'এলাকা', cell: (a) => [a.areaLabel || a.district || '—', a.area ? h('div', { class: 'small', text: a.area }) : null] },
      { label: 'ভাড়া', cell: (a) => [a.fareText || '—', a.fare ? h('div', { class: 'small', text: a.fare }) : null] },
      { label: 'রেটিং', cell: (a) => [a.ratingCount ? `${bn(a.rating)} (${bn(a.ratingCount)})` : '—',
        a.overchargeCount ? h('div', { class: 'small', text: `বলা ভাড়ার চেয়ে বেশি নিয়েছে: ${bn(a.overchargeCount)} বার` }) : null] },
      { label: 'বদল / নোট', cell: (a) => reviewCell(a) },
    ],
    id: (a) => a.id,
    owner: (a) => a.ownerUid,
    actions: (a, st) => statusActions('admin_ambulance_resolve', a.id, st, 'অ্যাম্বুলেন্স', a),
  },
  bloodBanks: {
    title: 'ব্লাড ব্যাংক',
    action: 'admin_bloodbanks',
    tabs: [['pending', 'অপেক্ষায়'], ['approved', 'অনুমোদিত'], ['suspended', 'বন্ধ'], ['rejected', 'বাতিল']],
    search: 'নাম, ফোন, লাইসেন্স, এলাকা',
    text: (b) => [b.name, b.phone, b.licenseNo, b.areaLabel, b.district, b.address],
    cols: [
      { label: 'নাম', main: true, cell: (b) => [b.name || '—', ' ', h('span', { class: 'chip pri', text: L.bankKind[b.kind] || b.kind }),
        b.addedByAdmin ? [' ', h('span', { class: 'chip', text: 'অ্যাডমিন যোগ করেছেন' })] : null,
        h('div', { class: 'chips' }, (b.services || []).map((x: string) => h('span', { class: 'chip', text: L.bankService[x] || x })))] },
      { label: 'লাইসেন্স', cell: (b) => b.licenseNo || '—' },
      { label: 'ফোন', cell: (b) => bn(b.phone) },
      { label: 'এলাকা', cell: (b) => [b.areaLabel || b.district || '—', b.address ? h('div', { class: 'small', text: b.address }) : null] },
      { label: 'সময়', cell: (b) => [hoursText(b), closedDaysNote(b)] },
      { label: 'তারিখ', cell: (b) => fmtDate(b.reviewedAt || b.createdAt) },
      { label: 'বদল / নোট', cell: (b) => reviewCell(b) },
    ],
    note: 'বেসরকারি ব্লাড ব্যাংকের লাইসেন্স স্বাস্থ্য অধিদপ্তরে যাচাই করুন। হাসপাতালের ব্লাড ব্যাংক হলে হাসপাতালে ফোন করে মিলিয়ে নিন।',
    id: (b) => b.id,
    owner: (b) => b.ownerUid,
    actions: (b, st) => [...statusActions('admin_bloodbank_resolve', b.id, st, 'ব্লাড ব্যাংক', b),
      h('button', { type: 'button', class: 'btn quiet', text: 'সম্পাদনা', onclick: () => {
        S.editBank = b;
        route(true);
        setTimeout(() => document.querySelector('.bank-form')?.scrollIntoView({ behavior: 'smooth' }), 50);
      } }),
      actionBtn('মুছুন', 'warn', () => act('admin_bloodbank_delete', { id: b.id }), 'ব্লাড ব্যাংক মুছে ফেলা হয়েছে', afterChange)],
    extra: () => c(BloodBankForm, { key: S.editBank ? `e:${S.editBank.id}` : 'new', edit: S.editBank || null }),
  },
};

/** অ্যাডমিন নিজে চেনা ব্লাড ব্যাংক যোগ করেন (সাথে সাথে তালিকায়), বা কোনোটার তথ্য ঠিক করেন (edit) */
const BloodBankForm = defineComponent({
  name: 'BloodBankForm',
  props: { edit: { type: Object as PropType<J>, default: null } },
  setup(p) {
    const e: J = p.edit || {};
    const blank = () => ({
      name: e.name ?? '', kind: e.kind || Object.keys(L.bankKind)[0], phone: e.phone ?? '', district: '', address: e.address ?? '', licenseNo: e.licenseNo ?? '',
      open24: e.open24 === true, openAt: e.openAt ?? '', closeAt: e.closeAt ?? '',
      services: [...(e.services || [])] as string[], closedDays: [...(e.closedDays || [])] as number[],
    });
    const f = reactive(blank());
    const districts = ref<string[]>([]);
    act('admin_districts').then((r: J) => {
      districts.value = r.items || [];
      if (e.district) nextTick(() => { f.district = e.district; });
    }).catch(() => {});
    const val = (ev: Event) => (ev.target as HTMLInputElement).value;
    const toggle = <T>(list: T[], v: T, on: boolean) => {
      const i = list.indexOf(v);
      if (on && i < 0) list.push(v);
      if (!on && i >= 0) list.splice(i, 1);
    };
    const body = () => ({
      name: f.name.trim(), kind: f.kind, phone: f.phone.trim(),
      district: f.district, address: f.address.trim(), licenseNo: f.licenseNo.trim(),
      open24: f.open24, openAt: f.openAt || '', closeAt: f.closeAt || '',
      // ফর্মের ক্রমে (আগের FormData-র মতো)
      services: Object.keys(L.bankService).filter((k) => f.services.includes(k)),
      closedDays: WEEKDAYS.map((_, i) => i).filter((i) => f.closedDays.includes(i)),
    });
    type TextKey = 'name' | 'phone' | 'address' | 'licenseNo' | 'openAt' | 'closeAt';
    const input = (name: TextKey, label: string, attrs: Record<string, unknown> = {}) => h('label', { class: 'field' }, h('span', { text: label }),
      h('input', { name, value: f[name], oninput: (ev: Event) => { f[name] = val(ev); }, ...attrs }));
    const edit = p.edit;

    return () => {
      const form = h('form', { class: 'card pad bank-form', onsubmit: (ev: Event) => ev.preventDefault() },
        input('name', 'নাম', { maxlength: 100, required: true }),
        h('label', { class: 'field' }, h('span', { text: 'ধরন' }),
          h('select', { name: 'kind', 'aria-label': 'ধরন', value: f.kind, onchange: (ev: Event) => { f.kind = val(ev); } }, Object.entries(L.bankKind).map(([k, v]) => h('option', { value: k, text: v })))),
        input('phone', 'ফোন (মোবাইল, ল্যান্ডলাইন বা হটলাইন)', { inputmode: 'tel', maxlength: 15 }),
        h('label', { class: 'field' }, h('span', { text: 'জেলা' }),
          h('select', { name: 'district', 'aria-label': 'জেলা', value: f.district, onchange: (ev: Event) => { f.district = val(ev); } },
            h('option', { value: '', text: 'জেলা বেছে নিন' }), districts.value.map((d) => h('option', { value: d, text: d })))),
        input('address', 'ঠিকানা', { maxlength: 200 }),
        input('licenseNo', 'লাইসেন্স নম্বর (বেসরকারি হলে লাগবে)', { maxlength: 60 }),
        h('label', { class: 'check' }, h('input', { type: 'checkbox', name: 'open24', checked: f.open24, onchange: (ev: Event) => { f.open24 = (ev.target as HTMLInputElement).checked; } }), h('span', { text: '২৪ ঘণ্টা খোলা' })),
        h('div', { class: 'field-row' }, input('openAt', 'খোলে', { type: 'time' }), input('closeAt', 'বন্ধ হয়', { type: 'time' })),
        h('div', { class: 'field' }, h('span', { text: 'সাপ্তাহিক বন্ধ' }), h('div', { class: 'checks' }, WEEKDAYS.map((v, i) => h('label', { class: 'check' },
          h('input', { type: 'checkbox', name: 'off', value: String(i), checked: f.closedDays.includes(i), onchange: (ev: Event) => toggle(f.closedDays, i, (ev.target as HTMLInputElement).checked) }),
          h('span', { text: v }))))),
        h('div', { class: 'field' }, h('span', { text: 'সেবা' }), h('div', { class: 'checks' }, Object.entries(L.bankService).map(([k, v]) => h('label', { class: 'check' },
          h('input', { type: 'checkbox', name: 'svc', value: k, checked: f.services.includes(k), onchange: (ev: Event) => toggle(f.services, k, (ev.target as HTMLInputElement).checked) }),
          h('span', { text: v }))))),
        edit
          ? h('div', { class: 'actions' },
            actionBtn('সংরক্ষণ', 'primary', async () => {
              await act('admin_bloodbank_edit', { id: edit.id, ...body() });
              S.editBank = null;
            }, 'তথ্য ঠিক করা হয়েছে', afterChange),
            h('button', { type: 'button', class: 'btn quiet', text: 'বাদ দিন', onclick: () => { S.editBank = null; route(true); } }))
          : actionBtn('যোগ করুন', 'primary', async () => {
            await act('admin_bloodbank_add', body());
            Object.assign(f, blank(), { district: '' });
          }, 'ব্লাড ব্যাংক যোগ হয়েছে, তালিকায় দেখাচ্ছে', afterChange));
      return edit
        ? [h('h2', { class: 'section-title', text: `সম্পাদনা: ${edit.name}` }),
          h('p', { class: 'note', text: 'মালিক থাকলে তাঁকে জানানো হবে যে Lifolo টিম তথ্য ঠিক করেছে।' }), form]
        : [h('h2', { class: 'section-title', text: 'চেনা ব্লাড ব্যাংক নিজে যোগ করুন' }),
          h('p', { class: 'note', text: 'যেমন সরকারি হাসপাতালের ব্লাড ব্যাংক বা সন্ধানী/রেড ক্রিসেন্ট। যোগ করলেই অ্যাপের তালিকায় দেখাবে।' }), form];
    };
  },
});

export function listingPage(kind: string) {
  return definePage(`PageListing_${kind}`, async (r) => {
    const def = LISTINGS[kind];
    const asked = r.params.get('s');
    const st = def.tabs.some(([k]) => k === asked) ? (asked as string) : 'pending';
    setTitle(def.title, L.listing[st]);
    const items = await getList(kind, st);
    const q = ref('');
    return () => {
      const shown = items.filter((x) => matches(q.value, ...def.text(x)));
      return [
        // গণনা টুলবারের শেষে (আগের মতো)
        toolbar({ tabs: def.tabs, current: st, base: kind, search: def.search, q: q.value, onSearch: (v) => { q.value = v; }, extra: h('span', { class: 'count', text: `${bn(shown.length)}টা` }) }),
        def.note && st === 'pending' ? h('p', { class: 'note', text: def.note }) : null,
        h('div', null, table(shown, [...def.cols, { label: '', cell: (x) => userLink(def.owner(x), 'ব্যবহারকারী') }], (x) => def.actions(x, st))),
        def.extra ? def.extra() : null,
      ];
    };
  });
}
