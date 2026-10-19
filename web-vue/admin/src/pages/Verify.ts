// যাচাই: কাগজের ছবি দেখে সব বিষয় টিক দিয়ে তবে অনুমোদন
// #/verify/pharmacies:<id>, #/verify/badges:<uid>[:change]
import { reactive, ref } from 'vue';
import { h } from '../h';
import { actionBtn, definePage, hold, reasonBox, reviewCell, userLink } from '../kit';
import { S, act, getList, go, loadOverview, setTitle } from '../state';
import { L, bn, errMsg, fmtDate, toast, type J } from '../util';
import { LISTINGS } from './Listing';

const VERIFY_KIND: Record<string, string> = { pharmacies: 'pharmacy', ambulances: 'ambulance', bloodBanks: 'bloodbank', badges: 'badge', labs: 'lab' };
const RESOLVE: Record<string, string> = { pharmacies: 'admin_pharmacy_resolve', ambulances: 'admin_ambulance_resolve', bloodBanks: 'admin_bloodbank_resolve', labs: 'admin_lab_resolve' };

/** আবেদনে যা লেখা হয়েছে (ছবির সাথে মেলানোর জন্য) */
function verifyFields(list: string, x: J): [string, string][] {
  const c = x.change || {};
  const area = [x.areaLabel || x.district, x.address || x.area].filter(Boolean).join(' · ');
  const hours = x.open24 ? '২৪ ঘণ্টা' : x.openAt ? `${bn(x.openAt)}–${bn(x.closeAt)}` : '';
  if (list === 'badges') {
    return [['নাম', c.name || x.name], ['পেশা', L.badge[c.type || x.type] || c.type || x.type], ['রেজি. নম্বর', c.regNo || x.regNo],
      ['কর্মস্থল', c.workplace || x.workplace], ['ইমেইল', x.email]];
  }
  if (list === 'ambulances') {
    return [['নাম', x.name], ['গাড়ির নম্বর', x.vehicleNo], ['ধরন', (x.types || []).map((t: string) => L.amb[t] || t).join(', ')],
      ['ফোন', bn(x.phone)], ['এলাকা', area], ['ভাড়ার তালিকা', x.fareText], ['ভাড়া (নোট)', x.fare],
      ['বেশি ভাড়ার অভিযোগ', x.overchargeCount ? `${bn(x.overchargeCount)} বার` : '']];
  }
  if (list === 'pharmacies') {
    return [['নাম', x.name], ['লাইসেন্স নম্বর', x.licenseNo], ['ফোন', bn(x.phone)], ['এলাকা ও ঠিকানা', area], ['সময়', hours]];
  }
  if (list === 'labs') {
    return [['নাম', x.name], ['লাইসেন্স নম্বর', x.licenseNo], ['ফোন', bn(x.phone)], ['এলাকা ও ঠিকানা', area], ['সময়', x.hours],
      ['স্বাস্থ্য অধিদপ্তরের তালিকায়', x.facilityId ? 'আছে (তালিকা থেকে বাছা)' : 'নেই (নিজে লেখা)']];
  }
  return [['নাম', x.name], ['ধরন', L.bankKind[x.kind] || x.kind], ['লাইসেন্স নম্বর', x.licenseNo], ['ফোন', bn(x.phone)],
    ['এলাকা ও ঠিকানা', area], ['সময়', hours]];
}

async function findForVerify(list: string, key: string) {
  const match = (it: J) => (list === 'badges' ? it.uid + (it.change ? ':change' : '') : it.id) === key;
  for (const st of ['pending', 'rejected']) {
    const items = await getList(list, st);
    const it = items.find(match);
    if (it) return { item: it, st };
  }
  return null;
}

export default definePage('PageVerify', async (r, { alive }) => {
  const [list, ...rest] = r.arg.split(':');
  const key = rest.join(':');
  const kind = VERIFY_KIND[list];
  if (!kind || !key) throw new Error('পাওয়া যায়নি');
  const back = `#/${list}`;
  setTitle('যাচাই', LISTINGS[list].title, () => [h('a', { class: 'btn quiet', href: back, text: 'ফিরে যান' })]);
  const found = await findForVerify(list, key);
  if (!found) {
    return () => h('div', { class: 'card pad' }, h('p', { text: 'এটা আর অপেক্ষার তালিকায় নেই (হয়তো আগেই সিদ্ধান্ত হয়েছে)।' }), h('a', { href: back, text: 'তালিকায় ফিরুন' }));
  }
  const x = found.item;
  const id = list === 'badges' ? x.uid : x.id;
  const correction = list === 'badges' && !!x.change;
  const v = await act('admin_verify_docs', { kind, id });
  if (!alive()) return;

  const missing = v.docs.filter((d: J) => d.required && !d.has);
  // টিক দেওয়ার তালিকা
  const ticked = reactive(new Set<string>());
  const approving = ref(false);

  const done = async () => {
    // অপেক্ষার তালিকা loadOverview নতুন করে দেয়; বাকিগুলো আবার আনা হবে
    for (const k of Object.keys(S.lists)) delete S.lists[k];
    await loadOverview().catch(() => {});
    go(back);
  };
  async function approve() {
    approving.value = true;
    try {
      if (list === 'badges') await act('admin_badge_resolve', { uid: id, approve: true, checks: [...ticked], ...(correction ? { correction: true } : {}) });
      else await act(RESOLVE[list], { id, status: 'approved', checks: [...ticked] });
      toast('যাচাই করে অনুমোদন দেওয়া হয়েছে');
      await done();
    } catch (e) {
      toast(errMsg(e));
      approving.value = false;
    }
  }

  const reason = hold(x, 'verify-reason');
  const note = hold(x, 'verify-note');
  // মালিক আছে কি না (অ্যাডমিনের যোগ করা ব্লাড ব্যাংকের মালিক নেই)
  const hasOwner = list === 'badges' || list === 'pharmacies' || !!x.ownerUid;

  return () => {
    const review = reviewCell(x);
    const n = v.checks.length - ticked.size;
    const left = missing.length ? 'দরকারি ছবি নেই — আগে ছবি চান' : n > 0 ? `আরও ${bn(n)}টা বিষয় বাকি` : 'সব মিলেছে';
    return h('div', { class: 'verify' },
      h('div', { class: 'card pad' }, h('h2', { class: 'section-title', text: 'কাগজের ছবি' }),
        h('div', { class: 'photos' }, v.photos.map((p: J) => {
          const meta = v.docs.find((d: J) => d.type === p.type) || {};
          if (!p.data) {
            return h('figure', { class: 'photo none' }, h('div', { class: 'ph', text: meta.required ? 'দরকারি ছবি দেওয়া হয়নি' : 'দেওয়া হয়নি (ঐচ্ছিক)' }),
              h('figcaption', null, p.label, meta.required ? [' ', h('span', { class: 'chip bad', text: 'দরকারি' })] : null));
          }
          const src = `data:image/jpeg;base64,${p.data}`;
          return h('figure', { class: 'photo' },
            h('img', { src, alt: p.label, onclick: () => { S.zoom = { src, label: p.label }; } }),
            h('figcaption', null, p.label, h('span', { class: 'small', text: ` · ${fmtDate(p.at)} · বড় করতে চাপুন` })));
        }))),
      h('div', { class: 'card pad' },
        h('h2', { class: 'section-title', text: 'আবেদনে যা লেখা' }),
        h('table', { class: 'kv' }, h('tbody', null, verifyFields(list, x).map(([k, val]) =>
          h('tr', null, h('th', { scope: 'row', text: k }), h('td', { text: val || '—' }))))),
        review === '—' ? null : h('div', { class: 'note' }, review),
        h('h2', { class: 'section-title', text: 'ছবি দেখে মিলিয়ে টিক দিন' }),
        h('ol', { class: 'checklist' }, v.checks.map((ck: J) => h('li', null,
          h('label', { for: `ck_${ck.id}` },
            h('input', { type: 'checkbox', id: `ck_${ck.id}`, checked: ticked.has(ck.id), onchange: (e: Event) => {
              if ((e.target as HTMLInputElement).checked) ticked.add(ck.id);
              else ticked.delete(ck.id);
            } }),
            h('span', { class: 'lbl', text: ck.label })),
          ck.format ? h('div', { class: 'fmt' }, h('b', { text: 'সঠিক রূপ: ' }), ck.format) : null,
          ck.link ? h('div', { class: 'fmt' }, h('a', { href: ck.link, target: '_blank', rel: 'noopener noreferrer', text: 'এখানে মিলিয়ে দেখুন ↗' })) : null))),
        h('div', { class: 'row' },
          h('button', { type: 'button', class: 'btn primary', disabled: approving.value || n > 0 || missing.length > 0, onclick: approve, text: correction ? 'সংশোধন মঞ্জুর' : 'অনুমোদন দিন' }),
          h('span', { class: 'small', text: left }))),
      // আলাদা কার্ড: এখানকার বাটনের ভুলে অনুমোদনের বাটন খুলে যায় না
      h('div', { class: 'card pad verify-side' },
        h('h2', { class: 'section-title', text: 'না মিললে' }),
        h('div', { class: 'actions' }, reasonBox(reason), actionBtn(correction ? 'সংশোধন বাতিল' : 'বাতিল', 'warn', () => (list === 'badges'
          ? act('admin_badge_resolve', { uid: id, approve: false, reason: reason.value.trim(), ...(correction ? { correction: true } : {}) })
          : act(RESOLVE[list], { id, status: 'rejected', reason: reason.value.trim() })), 'বাতিল করা হয়েছে', done)),
        hasOwner
          ? h('div', { class: 'actions' },
            h('input', { class: 'reason', maxlength: 200, placeholder: 'কোন ছবি লাগবে (মালিক দেখবেন)', 'aria-label': 'কোন ছবি লাগবে', value: note.value, oninput: (e: Event) => { note.value = (e.target as HTMLInputElement).value; } }),
            actionBtn('ছবি চান', 'tonal', () => act('admin_request_docs', { kind, id, note: note.value.trim() }), 'মালিককে ছবি দিতে বলা হয়েছে', done))
          : h('p', { class: 'note', text: 'এটা অ্যাডমিন যোগ করেছেন, মালিক নেই।' }),
        hasOwner ? h('div', { class: 'small' }, userLink(list === 'badges' ? x.uid : list === 'pharmacies' ? x.id : x.ownerUid, 'মালিকের সব তথ্য')) : null));
  };
});
