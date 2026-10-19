// ডেমো (শুধু মালিক, শুধু পরীক্ষার জন্য) — অ্যাপ/ওয়েবের লগইন পাতায় রোলে চাপলেই সেই ডেমো অ্যাকাউন্ট
import { ref, type Ref } from 'vue';
import { h } from '../h';
import { actionBtn, definePage, table } from '../kit';
import { act, route, setTitle } from '../state';
import { bn, errMsg } from '../util';

/** ডেমো বানানো/মোছা ধাপে ধাপে — প্রতিটা আলাদা অনুরোধ (সার্ভারের সময়ের সীমা) */
async function demoRun(kind: 'seed' | 'wipe', progress: Ref<string>) {
  if (kind === 'wipe') {
    for (let i = 0; i < 20; i++) {
      progress.value = 'মোছা হচ্ছে…';
      const r = await act('admin_demo_wipe');
      if (!r.more) {
        progress.value = '';
        return;
      }
      progress.value = `মোছা হচ্ছে — বাকি ${bn(r.left)}টা অ্যাকাউন্ট…`;
    }
    throw new Error('মোছা শেষ হয়নি — আবার চাপুন');
  }
  let step: number | null = 0;
  let label = 'আগের ডেমো মোছা আর অ্যাকাউন্ট';
  for (let i = 0; i < 40 && step !== null; i++) {
    progress.value = `বানানো হচ্ছে: ${label}…`;
    const r = await act('admin_demo_seed', { step });
    step = r.next;
    label = r.label || '';
  }
  progress.value = '';
}

export default definePage('PageDemo', async (_r, { alive }) => {
  setTitle('ডেমো (পরীক্ষা)', 'সব রোলের ডেমো অ্যাকাউন্ট আর ডামি তথ্য');
  let st;
  try {
    st = await act('admin_demo_status');
  } catch (e) {
    const msg = errMsg(e);
    return () => h('p', { class: 'note', text: msg });
  }
  if (!alive()) return;
  const when = st.active && st.at ? new Date(st.at).toLocaleString('bn-BD') : '';
  const progress = ref('');
  return () => [
    h('p', { class: 'note', text: st.active
      ? (st.partial ? 'ডেমো বানানো মাঝপথে থেমে আছে। "নতুন করে বানান" চাপুন।' : `ডেমো চালু আছে (বানানো ${when}, ${bn(st.docs || 0)}টা ডামি তথ্য)। অ্যাপ আর ওয়েবের লগইন পাতায় "ডেমো" অংশে রোলে চাপলেই সেই অ্যাকাউন্টে ঢোকা যায়।`)
      : 'ডেমো বন্ধ। "ডেমো বানান" চাপলে সব রোলের অ্যাকাউন্ট আর ডামি তথ্য তৈরি হবে।' }),
    h('div', { class: 'card pad' }, h('div', { class: 'field-row' },
      actionBtn(st.active ? 'নতুন করে বানান' : 'ডেমো বানান', st.active ? 'warn' : 'primary', () => demoRun('seed', progress), 'ডেমো তৈরি হয়েছে', () => route(true)),
      st.active ? actionBtn('সব ডেমো মুছুন', 'warn', () => demoRun('wipe', progress), 'সব ডেমো মুছে ফেলা হয়েছে', () => route(true)) : null),
    h('p', { class: 'note', 'aria-live': 'polite', text: progress.value })),
    h('h2', { class: 'section-title', text: 'রোল' }),
    table(st.roles || [], [
      { label: 'রোল', main: true, cell: (x) => x.role },
      { label: 'অ্যাকাউন্ট', cell: (x) => x.email },
    ]),
    h('p', { class: 'note', text: 'অ্যাডমিনের কাজ আপনার নিজের অ্যাকাউন্টেই পরীক্ষা করুন — ডেমো বানালে অনুমোদনের অপেক্ষায় থাকা ডামি জিনিসও আসে। নতুন করে বানালে আগের সব ডেমো (পরীক্ষার সময় বানানোসহ) মুছে যায়।' }),
  ];
});
