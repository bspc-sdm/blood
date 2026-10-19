// রিপোর্টের ছক (পরীক্ষার ঘর, একক, স্বাভাবিক মাত্রা)
import { reactive, ref } from 'vue';
import { h, icon } from '../h';
import { definePage, table, type Painter } from '../kit';
import { act, go, setTitle } from '../state';
import { bn, errMsg, matches, toast } from '../util';

type Range = [number | null, number | null];
interface Param {
  key: string;
  name: string;
  unit?: string;
  r?: Range;
  m?: Range;
  f?: Range;
  text?: string;
  options?: string[];
  long?: boolean;
}
interface Test {
  code: string;
  name: string;
  bn?: string;
  aliases?: string[];
  params: Param[];
  hidden?: boolean;
}

export default definePage('PageLabTests', async (r, { alive }) => {
  const res = await act('labtests_get');
  if (!alive()) return;
  const tests: Test[] = res.tests || [];
  const code = r.params.get('t');
  if (code != null) return labTestEditor(tests, code);
  setTitle('রিপোর্টের ছক', `${bn(tests.length)}টা পরীক্ষা`);
  const q = ref('');
  return () => {
    const shown = tests.filter((t) => matches(q.value, t.name, t.bn, ...(t.aliases || [])));
    return [
      h('div', { class: 'toolbar' }, h('div', { class: 'search' }, icon('search'), h('input', { placeholder: 'পরীক্ষা খুঁজুন', value: q.value, oninput: (e: Event) => { q.value = (e.target as HTMLInputElement).value; } })),
        h('button', { class: 'btn primary', type: 'button', text: 'নতুন পরীক্ষা', onclick: () => go('#/labtests?t=') })),
      h('p', { class: 'note', text: 'প্যাথলজি রিপোর্ট দিতে গেলে এই ঘরগুলো ভরা পায়, শুধু ফল লেখে; বেশি/কম নিজে থেকে বসে। ডাক্তারের লেখা পরীক্ষার নাম "অন্য নাম"-এর সাথে মিলিয়ে ছক বাছা হয়। মাত্রাগুলো সাধারণ প্রাপ্তবয়স্কের — ল্যাব নিজের মেশিনের মাত্রা রাখতে পারে।' }),
      h('div', null, table(shown, [
        { label: 'পরীক্ষা', main: true, cell: (t) => [t.name, h('div', { class: 'small', text: t.bn || '' })] },
        { label: 'ঘর', cell: (t) => bn(t.params.length) },
        { label: 'অন্য নাম', cell: (t) => h('div', { class: 'small', text: (t.aliases || []).slice(0, 4).join(', ') }) },
        { label: '', cell: (t) => (t.hidden ? h('span', { class: 'chip', text: 'লুকানো' }) : '') },
      ], null, (t) => go(`#/labtests?t=${encodeURIComponent(t.code)}`))),
    ];
  };
});

function labTestEditor(tests: Test[], code: string): Painter {
  const orig = tests.find((t) => t.code === code);
  const t = reactive<Test>(orig ? JSON.parse(JSON.stringify(orig)) : { code: '', name: '', bn: '', aliases: [], params: [{ key: '', name: '' }] });
  setTitle(orig ? orig.name : 'নতুন পরীক্ষা', 'রিপোর্টের ছক');
  const err = ref('');
  const saving = ref(false);
  /** লেখা ঘরের মান আলাদা রাখা — তালিকা/সংখ্যা থেকে আবার লেখা বানালে লেখার মাঝে কমা/বিন্দু হারায় না */
  const typed = new WeakMap<object, Record<string, string>>();
  const textOf = (o: object, k: string, init: () => string) => {
    let m = typed.get(o);
    if (!m) typed.set(o, (m = {}));
    return (m[k] ??= init());
  };
  const setText = (o: object, k: string, v: string) => {
    textOf(o, k, () => v);
    typed.get(o)![k] = v;
  };
  const val = (e: Event) => (e.target as HTMLInputElement).value;

  const numIn = (p: Param, k: 'r' | 'm' | 'f', i: 0 | 1) => h('input', {
    class: 'cell-input', inputmode: 'decimal',
    value: textOf(p, `${k}${i}`, () => (p[k] && p[k]![i] != null ? String(p[k]![i]) : '')),
    oninput: (e: Event) => {
      const raw = val(e);
      setText(p, `${k}${i}`, raw);
      const v = raw.trim();
      p[k] = p[k] || [null, null];
      p[k]![i] = v === '' ? null : Number(v);
      if (p[k]![0] == null && p[k]![1] == null) delete p[k];
    },
  });
  // আগের প্যানেলে এখানে style অ্যাট্রিবিউট ছিল (প্রস্থ, flex) — প্যানেলের CSP সেটা আটকায়, তাই বাদ (দেখতে একই)
  const pair = (p: Param, k: 'r' | 'm' | 'f') => h('span', null, numIn(p, k, 0), '–', numIn(p, k, 1));
  const move = (a: number, b: number) => {
    [t.params[a], t.params[b]] = [t.params[b], t.params[a]];
  };
  async function save() {
    err.value = '';
    saving.value = true;
    try {
      const out = await act('admin_labtests_set', { test: JSON.parse(JSON.stringify(t)) });
      toast('সেভ হয়েছে');
      go(`#/labtests?t=${encodeURIComponent(out.test.code)}`);
    } catch (e) {
      err.value = errMsg(e);
      saving.value = false;
    }
  }

  return () => [
    h('div', { class: 'toolbar' }, h('a', { class: 'btn quiet', href: '#/labtests', text: '← সব পরীক্ষা' })),
    h('div', { class: 'card pad grid' },
      h('label', null, 'পরীক্ষার নাম', h('input', { class: 'cell-input', value: t.name, maxlength: 80, placeholder: 'যেমন Serum Magnesium', oninput: (e: Event) => { t.name = val(e); } })),
      h('label', null, 'বাংলা নাম', h('input', { class: 'cell-input', value: t.bn || '', maxlength: 80, placeholder: 'বাংলা নাম (ঐচ্ছিক)', oninput: (e: Event) => { t.bn = val(e); } })),
      h('label', null, 'অন্য নাম (মেলানোর জন্য)', h('input', { class: 'cell-input', value: textOf(t, 'aliases', () => (t.aliases || []).join(', ')), placeholder: 'ডাক্তার যেভাবে লেখেন: s magnesium, mg', oninput: (e: Event) => {
        setText(t, 'aliases', val(e));
        t.aliases = val(e).split(',').map((x) => x.trim()).filter(Boolean);
      } })),
      h('label', { class: 'check' }, h('input', { type: 'checkbox', checked: !!t.hidden, onchange: (e: Event) => { t.hidden = (e.target as HTMLInputElement).checked; } }), 'লুকানো (নতুন রিপোর্টে আর আসবে না)')),
    h('p', { class: 'note', text: 'সংখ্যার ঘরে স্বাভাবিক মাত্রা দিন — "সবার" অথবা পুরুষ/নারী আলাদা। শুধু ওপরের সীমা থাকলে নিচেরটা খালি রাখুন (যেমন কোলেস্টেরল < ২০০)। লেখা-ফলের ঘরে (Negative/Positive) মাত্রা খালি রেখে "লেখা-ফল" আর "বাছাই" দিন। এক্স-রে/আল্ট্রাসনোর মতো রিপোর্টে "লম্বা"।' }),
    h('div', { class: 'card table-wrap' }, h('table', { class: 't' },
      h('thead', null, h('tr', null, ['ঘর', 'একক', 'স্বাভাবিক (সবার)', 'পুরুষ', 'নারী', 'লেখা-ফল', 'বাছাই', '', ''].map((x) => h('th', { text: x })))),
      h('tbody', null, t.params.map((p, i) => h('tr', null,
        h('td', { 'data-label': 'ঘর' }, h('input', { class: 'cell-input', value: p.name || '', maxlength: 60, oninput: (e: Event) => { p.name = val(e); } })),
        h('td', { 'data-label': 'একক' }, h('input', { class: 'cell-input', value: p.unit || '', maxlength: 20, oninput: (e: Event) => { p.unit = val(e); } })),
        h('td', { 'data-label': 'স্বাভাবিক (সবার)' }, pair(p, 'r')),
        h('td', { 'data-label': 'পুরুষ' }, pair(p, 'm')),
        h('td', { 'data-label': 'নারী' }, pair(p, 'f')),
        h('td', { 'data-label': 'লেখা-ফল' }, h('input', { class: 'cell-input', value: p.text || '', maxlength: 40, placeholder: 'যেমন Negative', oninput: (e: Event) => { p.text = val(e); } })),
        h('td', { 'data-label': 'বাছাই' }, h('input', { class: 'cell-input', value: textOf(p, 'options', () => (p.options || []).join(', ')), placeholder: 'কমা দিয়ে', oninput: (e: Event) => {
          setText(p, 'options', val(e));
          p.options = val(e).split(',').map((x) => x.trim()).filter(Boolean);
        } })),
        h('td', { 'data-label': 'লম্বা লেখা' }, h('label', { class: 'check' }, h('input', { type: 'checkbox', checked: !!p.long, onchange: (e: Event) => { p.long = (e.target as HTMLInputElement).checked; } }), 'লম্বা')),
        h('td', { class: 'act' }, h('div', { class: 'actions' },
          h('button', { class: 'icon-btn', type: 'button', title: 'ওপরে', disabled: i === 0, onclick: () => move(i - 1, i), text: '↑' }),
          h('button', { class: 'icon-btn', type: 'button', title: 'নিচে', disabled: i === t.params.length - 1, onclick: () => move(i + 1, i), text: '↓' }),
          h('button', { class: 'icon-btn', type: 'button', title: 'সরান', onclick: () => { t.params.splice(i, 1); } }, icon('close'))))))))),
    h('div', { class: 'reply-actions' },
      h('button', { class: 'btn tonal', type: 'button', text: 'আরেকটা ঘর', onclick: () => { t.params.push({ key: '', name: '' }); } }),
      h('button', { class: 'btn primary', type: 'button', disabled: saving.value, onclick: save, text: 'সেভ করুন' })),
    h('p', { class: 'error', role: 'alert', text: err.value }),
  ];
}
