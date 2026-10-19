// বাছাইয়ের তালিকা: বিশেষজ্ঞতা, বাসায় সেবা, অ্যাম্বুলেন্সের ধরন, ব্লাড ব্যাংকের সেবা
// যোগ, নাম বদল, লুকানো, ক্রম — মোছা যায় না (আগে কেউ বেছে রাখলে তাঁর প্রোফাইলে ভুল দেখাত)
import { reactive, ref } from 'vue';
import { h, icon } from '../h';
import { definePage } from '../kit';
import { act, setTitle } from '../state';
import { errMsg, toast, type J } from '../util';

const CATALOG_LISTS: [string, string, string][] = [
  ['specialty', 'doctor', 'ডাক্তারের বিশেষজ্ঞতা'], ['specialty', 'nurse', 'নার্সের বিশেষজ্ঞতা'],
  ['specialty', 'nutritionist', 'পুষ্টিবিদের বিশেষজ্ঞতা'], ['specialty', 'trainer', 'ফিটনেস ট্রেইনারের বিশেষজ্ঞতা'],
  ['homeService', 'doctor', 'বাসায় সেবা: ডাক্তার'], ['homeService', 'nurse', 'বাসায় সেবা: নার্স'],
  ['ambulanceType', 'all', 'অ্যাম্বুলেন্সের ধরন'], ['bloodBankService', 'all', 'ব্লাড ব্যাংকের সেবা'],
];

interface Item {
  code: string;
  bn: string;
  en: string;
  hidden: boolean;
}

export default definePage('PageCatalog', async (r) => {
  const idx = Math.max(0, CATALOG_LISTS.findIndex(([k, t]) => `${k}.${t}` === r.params.get('l')));
  const [kind, type, title] = CATALOG_LISTS[idx];
  setTitle('বাছাইয়ের তালিকা', title);
  const res = await act('catalog_get');
  const rows = reactive<Item[]>(((res.lists[kind] || {})[type] || []).map((i: J) => ({ ...i })));
  const err = ref('');
  const saving = ref(false);
  const swap = (a: number, b: number) => {
    [rows[a], rows[b]] = [rows[b], rows[a]];
  };
  async function save() {
    err.value = '';
    saving.value = true;
    try {
      const out = await act('admin_catalog_set', { kind, type, items: rows.map((x) => ({ code: x.code, bn: (x.bn || '').trim(), en: (x.en || '').trim(), hidden: !!x.hidden })) });
      rows.splice(0, rows.length, ...out.items.map((i: J) => ({ ...i })));
      toast('সেভ হয়েছে — অ্যাপে কয়েক ঘণ্টার মধ্যে দেখাবে');
    } catch (e) {
      err.value = errMsg(e);
    }
    saving.value = false;
  }
  const val = (e: Event) => (e.target as HTMLInputElement).value;

  // আগের প্যানেলে এখানে style অ্যাট্রিবিউট ছিল (flex-wrap:nowrap) — প্যানেলের CSP সেটা আটকায়, তাই বাদ (দেখতে একই)
  return () => [
    h('div', { class: 'seg tabs-wrap' }, CATALOG_LISTS.map(([k, t, label], i) =>
      h('a', { href: `#/catalog?l=${k}.${t}`, class: i === idx ? 'on' : null, text: label }))),
    h('p', { class: 'note', text: 'নতুন নাম যোগ, বানান ঠিক বা লুকানো যায়। মোছা যায় না — আগে কেউ বেছে রাখলে তাঁর প্রোফাইলে ভুল দেখাত; লুকালে নতুন করে কেউ বাছতে পারবেন না।' }),
    h('div', { class: 'card table-wrap' }, h('table', { class: 't' },
      h('thead', null, h('tr', null, ['বাংলা নাম', 'English name', 'কোড', '', ''].map((x) => h('th', { text: x })))),
      h('tbody', null, rows.map((it, i) => h('tr', { class: it.hidden ? 'done' : null },
        h('td', { 'data-label': 'বাংলা' }, h('input', { class: 'cell-input', value: it.bn || '', maxlength: 60, oninput: (e: Event) => { it.bn = val(e); } })),
        h('td', { 'data-label': 'English' }, h('input', { class: 'cell-input', value: it.en || '', maxlength: 60, oninput: (e: Event) => { it.en = val(e); } })),
        h('td', { 'data-label': 'কোড', class: 'small', text: it.code || 'নতুন' }),
        h('td', { 'data-label': 'লুকানো' }, h('label', { class: 'check' }, h('input', { type: 'checkbox', checked: !!it.hidden, onchange: (e: Event) => { it.hidden = (e.target as HTMLInputElement).checked; } }), 'লুকানো')),
        h('td', { class: 'act' }, h('div', { class: 'actions' },
          h('button', { class: 'icon-btn', type: 'button', title: 'ওপরে', disabled: i === 0, onclick: () => swap(i - 1, i), text: '↑' }),
          h('button', { class: 'icon-btn', type: 'button', title: 'নিচে', disabled: i === rows.length - 1, onclick: () => swap(i + 1, i), text: '↓' }),
          !it.code ? h('button', { class: 'icon-btn', type: 'button', title: 'সরান', onclick: () => { rows.splice(i, 1); } }, icon('close')) : null))))))),
    h('div', { class: 'reply-actions' },
      h('button', { class: 'btn tonal', type: 'button', text: 'নতুন নাম যোগ', onclick: () => { rows.push({ code: '', bn: '', en: '', hidden: false }); } }),
      h('button', { class: 'btn primary', type: 'button', disabled: saving.value, onclick: save, text: 'সেভ করুন' })),
    h('p', { class: 'error', role: 'alert', text: err.value }),
  ];
});
