// ফিচার সুইচ (backend/docs/FEATURES.md): নতুন সুবিধা বন্ধ অবস্থায় আসে — এখানে ধাপে ধাপে চালু:
// বন্ধ → পাইলট (বাছাই করা ব্যবহারকারী + চাইলে ডেমো অ্যাকাউন্ট) → সবার জন্য (আগে নিশ্চিত করতে হয়)। অ্যাপ আপডেট লাগে না।
import { reactive, ref, shallowRef } from 'vue';
import { h, icon, type Kid } from '../h';
import { actionBtn, definePage, table, userLink } from '../kit';
import { act, setTitle } from '../state';
import { ago, bn, errMsg, fmtDT, type J } from '../util';

/** চাবি → বাংলা নাম আর এক লাইনের বর্ণনা (সার্ভারের Features.KEYS-এর ক্রমে আসে) */
export const FEATURE_INFO: Record<string, { name: string; desc: string }> = {
  calls_audio: { name: 'অডিও কল', desc: 'অ্যাপের ভেতরে অডিও কল (অ্যাপয়েন্টমেন্ট ছাড়া সব জায়গায়)' }, // bn-ok
  calls_video: { name: 'ভিডিও কল', desc: 'ভিডিও কল, "ভিডিওতে ধরুন" আর অডিও থেকে ভিডিওতে যাওয়া' }, // bn-ok
  appt_video: { name: 'ভিডিও অ্যাপয়েন্টমেন্ট', desc: 'ডাক্তারের ভিডিও অ্যাপয়েন্টমেন্টে যোগ দেওয়া আর যোগ দেওয়ার পাতা' }, // bn-ok
  chat: { name: 'মেসেজ', desc: 'অ্যাপের ভেতরে লেখা মেসেজ' }, // bn-ok
  chat_media: { name: 'মেসেজে ছবি ও লোকেশন', desc: 'মেসেজে ছবি আর লোকেশন পাঠানো' }, // bn-ok
  online_chamber: { name: 'অনলাইন চেম্বার', desc: 'অনলাইন চেম্বার বানানো, বুকিং আর বিকাশে ফি' }, // bn-ok
  inv_pharmacy: { name: 'ফার্মেসির রসিদ', desc: 'খাতার বিক্রির রসিদ আর অনলাইন অর্ডার ডেলিভারির লাইন' }, // bn-ok
  inv_lab: { name: 'ল্যাবের বিল ও রসিদ', desc: 'প্যাথলজির বিল আর রসিদ' }, // bn-ok
  inv_chamber: { name: 'চেম্বারের রসিদ', desc: 'ডাক্তার/সহকারীর ফি রসিদ আর চেম্বার ডেস্ক' }, // bn-ok
  inv_ambulance: { name: 'অ্যাম্বুলেন্সের ভাড়ার রসিদ', desc: 'ট্রিপ শেষ করা আর ভাড়ার রসিদ' }, // bn-ok
  my_expenses: { name: 'আমার রসিদ ও খরচ', desc: 'রোগীর "আমার রসিদ ও খরচ" (শুধু চালু ধরনের রসিদ দেখায়)' }, // bn-ok
  desktop: { name: 'ডেস্কটপ অ্যাপ', desc: 'ডেস্কটপ অ্যাপ (রেজিস্টার, অফলাইন) আর ডাউনলোডের লিংক' }, // bn-ok
  pay_manual: { name: 'পেমেন্ট — মূল সুইচ', desc: 'বিকাশ/নগদ/রকেট/উপায়ে সরাসরি টাকা পাঠানো আর "টাকা পাঠিয়েছি" — বাকি পেমেন্টের আগে এটা' }, // bn-ok
  pay_chamber_advance: { name: 'চেম্বারে আগাম ফি', desc: 'চেম্বারের সিরিয়ালে আগাম ফি (ডাক্তার চাইলে)' }, // bn-ok
  pay_delivery: { name: 'হোম ডেলিভারির আগাম টাকা', desc: 'ফার্মেসির হোম ডেলিভারিতে আগে টাকা' }, // bn-ok
  pay_lab: { name: 'ল্যাবের টাকা', desc: 'বাসা থেকে নমুনা নেওয়া আর ল্যাবের বিলের টাকা' }, // bn-ok
  pay_ambulance: { name: 'অ্যাম্বুলেন্সের ভাড়া', desc: 'ট্রিপের পর ভাড়া মোবাইল ব্যাংকিংয়ে' }, // bn-ok
  pay_due: { name: 'রসিদের বাকি টাকা', desc: 'রসিদে বাকি থাকলে রোগী অ্যাপ থেকে দেন' }, // bn-ok
  pay_bkash_pgw: { name: 'বিকাশ গেটওয়ে (লাইফলোর আয়)', desc: 'বিকাশ চেকআউট — শুধু লাইফলোর নিজের আয়ের জন্য' }, // bn-ok
  practice_systems: { name: 'চিকিৎসা পদ্ধতি', desc: 'চিকিৎসা পদ্ধতি অনুযায়ী ডাক্তার খোঁজা ও দেখানো' }, // bn-ok
  pro_physio: { name: 'ফিজিওথেরাপিস্ট', desc: 'ফিজিওথেরাপিস্টের ব্যাজ, তালিকা আর অ্যাপয়েন্টমেন্ট' }, // bn-ok
  pro_psychologist: { name: 'সাইকোলজিস্ট / কাউন্সেলর', desc: 'সাইকোলজিস্ট/কাউন্সেলরের ব্যাজ, তালিকা আর অ্যাপয়েন্টমেন্ট' }, // bn-ok
  health_consent: { name: 'স্বাস্থ্য তথ্যের সম্মতি', desc: 'স্বাস্থ্য তথ্য দেওয়ার আগে রোগীর স্পষ্ট সম্মতি (দেশের বাইরের সার্ভারেও) — জরুরি সেবা কখনো আটকায় না' }, // bn-ok
};
export const featureName = (key: string) => FEATURE_INFO[key]?.name || key;

/** টাকার লেনদেনের সুবিধা: আইনি পরামর্শের আগে "সবার জন্য" নয় (payments security review) */
export const LEGAL_HOLD = (key: string) => key === 'online_chamber' || key.startsWith('pay_');
export const LEGAL_TEXT = 'আইনজীবীর মত নেওয়ার আগে সবার জন্য চালু করবেন না'; // bn-ok

export const MODE_LABEL: Record<string, string> = { off: 'বন্ধ', pilot: 'পাইলট', on: 'সবার জন্য' }; // bn-ok
const modeChip = (mode: string) => h('span', { class: `chip ${mode === 'on' ? 'good' : mode === 'pilot' ? 'pri' : 'bad'}`, text: MODE_LABEL[mode] || mode });

/** একটা কার্ডের খোঁজার অবস্থা */
interface Finder {
  q: string;
  busy: boolean;
  error: string;
  results: J[] | null;
}

export default definePage('PageFeatures', async (_r, { alive }) => {
  setTitle('ফিচার সুইচ', 'নতুন সুবিধা ধাপে ধাপে চালু করুন — অ্যাপ আপডেট ছাড়াই'); // bn-ok
  const data = shallowRef<J>(await act('admin_features_get'));
  if (!alive()) return;
  const finders = reactive<Record<string, Finder>>({});
  const finder = (key: string) => (finders[key] ||= { q: '', busy: false, error: '', results: null });
  const tick = ref(0);

  const reload = async () => {
    data.value = await act('admin_features_get');
    tick.value++;
  };
  const save = (item: J, patch: Record<string, unknown>) => () =>
    act('admin_feature_set', { key: item.key, mode: item.mode, ...patch });

  async function search(item: J) {
    const f = finder(item.key);
    const q = f.q.trim();
    if (q.length < 3) {
      f.error = 'ইমেইল বা ফোন নম্বর লিখুন'; // bn-ok
      f.results = null;
      return;
    }
    f.busy = true;
    f.error = '';
    try {
      const r = await act('admin_user_find', { q });
      f.results = r.items || [];
    } catch (e) {
      f.error = errMsg(e);
      f.results = null;
    } finally {
      f.busy = false;
    }
  }

  const modeButtons = (item: J): Kid => h('div', { class: 'actions feature-modes', role: 'group', 'aria-label': 'অবস্থা' }, // bn-ok
    (['off', 'pilot', 'on'] as const).map((m) => item.mode === m
      ? h('button', { type: 'button', class: 'btn primary', 'aria-pressed': 'true', disabled: true, text: MODE_LABEL[m] })
      // "সবার জন্য": প্রথম চাপে "নিশ্চিত? আবার চাপুন" (সবার ফোনে সাথে সাথে চালু হয়)
      : actionBtn(MODE_LABEL[m], m === 'on' ? 'warn' : 'quiet', save(item, { mode: m }), `${featureName(item.key)}: ${MODE_LABEL[m]}`, reload, `${item.key}-${m}-${tick.value}`)));

  const pilotList = (item: J): Kid => {
    const f = finder(item.key);
    const pilots: J[] = item.pilots || [];
    const ids: string[] = item.pilotUids || [];
    return h('div', { class: 'feature-pilots' },
      h('b', { text: `পাইলট ব্যবহারকারী (${bn(pilots.length)})` }), // bn-ok
      pilots.length
        ? table(pilots, [
          { label: 'নাম', main: true, cell: (u) => [u.name || '—', u.missing ? [' ', h('span', { class: 'chip bad', text: 'অ্যাকাউন্ট নেই' })] : null] }, // bn-ok
          { label: 'ইমেইল', cell: (u) => u.email || '—' },
          { label: 'ফোন', cell: (u) => bn(u.phone) || '—' },
        ], (u) => [
          userLink(u.uid, 'দেখুন'),
          actionBtn('সরান', 'quiet', save(item, { pilotUids: ids.filter((x) => x !== u.uid) }), 'পাইলট থেকে সরানো হয়েছে', reload, `rm-${item.key}-${u.uid}-${tick.value}`), // bn-ok
        ])
        : h('p', { class: 'note', text: 'এখনো কেউ নেই। ইমেইল বা ফোন নম্বর দিয়ে খুঁজে যোগ করুন।' }), // bn-ok
      h('form', { class: 'field-row', onsubmit: (e: Event) => { e.preventDefault(); void search(item); } },
        h('input', { type: 'search', value: f.q, placeholder: 'ইমেইল বা ফোন নম্বর', 'aria-label': `${featureName(item.key)}: পাইলট খুঁজুন`, // bn-ok
          oninput: (e: Event) => { f.q = (e.target as HTMLInputElement).value; } }),
        h('button', { class: 'btn tonal', type: 'submit', disabled: f.busy }, icon('search'), 'খুঁজুন')),
      f.error ? h('p', { class: 'error', role: 'alert', text: f.error }) : null,
      f.results
        ? (f.results.length
          ? table(f.results, [
            { label: 'নাম', main: true, cell: (u) => u.name || '—' },
            { label: 'ইমেইল', cell: (u) => u.email || '—' },
            { label: 'ফোন', cell: (u) => bn(u.phone) || '—' },
          ], (u) => (ids.includes(u.uid)
            ? h('span', { class: 'chip good', text: 'পাইলটে আছেন' }) // bn-ok
            : actionBtn('যোগ করুন', 'primary', save(item, { pilotUids: [...ids, u.uid] }), 'পাইলটে যোগ হয়েছে', async () => { // bn-ok
              f.results = null;
              f.q = '';
              await reload();
            }, `add-${item.key}-${u.uid}-${tick.value}`)))
          : h('p', { class: 'note', text: 'কাউকে পাওয়া যায়নি।' })) // bn-ok
        : null);
  };

  const card = (item: J): Kid => {
    const info = FEATURE_INFO[item.key] || { name: item.key, desc: '' };
    const needs: string[] = item.needs || [];
    const last = item.last;
    return h('section', { class: 'card pad feature', 'data-key': item.key, 'aria-label': info.name },
      h('div', { class: 'ctl' },
        h('div', { class: 'what' },
          h('b', null, info.name, ' ', modeChip(item.mode), item.demo && item.mode === 'pilot' ? [' ', h('span', { class: 'chip', text: 'ডেমো' })] : null), // bn-ok
          h('div', { class: 'note', text: info.desc }),
          needs.length ? h('div', { class: 'small', text: `আগে চালু থাকতে হবে: ${needs.map(featureName).join(', ')}` }) : null, // bn-ok
          item.twoParty ? h('div', { class: 'small', text: 'দুজনের মধ্যে: দুজনেরই চালু থাকলে তবেই কাজ করে' }) : null, // bn-ok
          last ? h('div', { class: 'small', text: `শেষ বদল: ${ago(last.at)}${last.byEmail ? ` — ${last.byEmail}` : ''}` }) : null, // bn-ok
          LEGAL_HOLD(item.key) ? h('div', { class: 'small feature-legal', role: 'note' }, '⚠ ', LEGAL_TEXT) : null),
        modeButtons(item)),
      item.mode === 'pilot'
        ? [
          h('label', { class: 'feature-demo' },
            h('input', { type: 'checkbox', checked: !!item.demo, onchange: async (e: Event) => {
              const box = e.target as HTMLInputElement;
              box.disabled = true;
              try {
                await act('admin_feature_set', { key: item.key, mode: item.mode, demo: box.checked });
                await reload();
              } catch (err) {
                box.checked = !box.checked;
                window.alert(errMsg(err));
              } finally {
                box.disabled = false;
              }
            } }),
            ' ডেমো অ্যাকাউন্টে চালু'), // bn-ok
          pilotList(item),
        ]
        : item.mode === 'off' && (item.pilotUids || []).length
          ? h('p', { class: 'small', text: `পাইলট তালিকায় ${bn(item.pilotUids.length)} জন রাখা আছে — "পাইলট" চাপলে আবার তাঁদের জন্য চালু হবে।` }) // bn-ok
          : null);
  };

  const history = (): Kid => {
    const items: J[] = data.value.history || [];
    return [
      h('h2', { class: 'section-title', text: 'বদলের ইতিহাস' }), // bn-ok
      table(items, [
        { label: 'কখন', main: true, cell: (x) => fmtDT(x.at) },
        { label: 'ফিচার', cell: (x) => featureName(x.key) }, // bn-ok
        { label: 'অবস্থা', cell: (x) => [x.prevMode ? `${MODE_LABEL[x.prevMode] || x.prevMode} → ` : '', modeChip(x.mode)] }, // bn-ok
        { label: 'পাইলট', cell: (x) => bn(x.pilotCount) }, // bn-ok
        { label: 'ডেমো', cell: (x) => (x.demo ? 'হ্যাঁ' : 'না') }, // bn-ok
        { label: 'কে', cell: (x) => x.byEmail || '—' }, // bn-ok
      ]),
    ];
  };

  return () => [
    data.value.allOn ? h('p', { class: 'note', text: 'পরীক্ষার সার্ভার: সব ফিচার সবার জন্য চালু (FEATURES_ALL_ON)।' }) : null, // bn-ok
    h('p', { class: 'note', text: 'বন্ধ: কেউ পায় না। পাইলট: শুধু তালিকার মানুষ (আর চাইলে ডেমো অ্যাকাউন্ট)। সবার জন্য: সবাই। বদলালে কয়েক সেকেন্ডের মধ্যে ফোন আর ওয়েবে পৌঁছায়।' }), // bn-ok
    (data.value.items || []).map(card),
    history(),
  ];
});
