// ভাষা: বাংলা (মূল) আর ইংরেজি — অ্যাপের tr()/num()-এর মতো। ভাষা বদলালে পুরো পাতা নতুন করে আঁকা হয়।
// Vue: ভাষা একটা ref — tr()/num() যেখানে পড়া হয় (টেমপ্লেট, computed) সেখানে নিজে থেকেই আবার আঁকে।
import { ref, type Ref } from 'vue';
import { store } from './store';

export type Lang = 'bn' | 'en';

const KEY = 'lf.lang';
const lang: Ref<Lang> = ref((store.get(KEY) as Lang) === 'en' ? 'en' : 'bn');

export function getLang(): Lang {
  return lang.value;
}
export const isBangla = () => lang.value === 'bn';

export function setLang(l: Lang) {
  if (l === lang.value) return;
  lang.value = l;
  store.set(KEY, l);
  document.documentElement.lang = l;
}

/** এখনকার ভাষা (ref) — watch করতে */
export function useLang(): Readonly<Ref<Lang>> {
  return lang;
}

const MINOR = new Set(['a', 'an', 'the', 'and', 'but', 'or', 'nor', 'for', 'so', 'yet', 'as', 'at', 'by', 'in', 'of', 'off', 'on', 'per', 'to', 'up', 'via', 'with', 'from', 'into', 'onto', 'over', 'than', 'vs']);
const UNITS = new Set(['km', 'kg', 'mg', 'ml', 'g', 'cm', 'yrs', 'min', 'sec', 'e.g.', 'i.e.']);
const titleCache = new Map<string, string>();

function isSentence(s: string) {
  const t = s.trim();
  if (!t) return true;
  if ('.?!:;'.includes(t[t.length - 1])) return true;
  if (/[.?!;](\s|$)/.test(t)) return true;
  return t.split(' ').filter(Boolean).length > 8;
}

function words(s: string) {
  const parts = s.split(' ');
  let last = -1;
  parts.forEach((w, i) => {
    if (w.trim()) last = i;
  });
  return parts
    .map((w, i) => {
      const core = w.replace(/^[^\p{L}\p{N}]+|[^\p{L}\p{N}]+$/gu, '').toLowerCase();
      const k = w.search(/\p{L}/u);
      if (k < 0) return w;
      const ch = w[k];
      if (ch !== ch.toLowerCase() || ch === ch.toUpperCase()) return w;
      if (UNITS.has(core)) return w;
      if (i !== 0 && i !== last && MINOR.has(core)) return w;
      if (/\d/.test(w.slice(0, k))) return w;
      return w.slice(0, k) + ch.toUpperCase() + w.slice(k + 1);
    })
    .join(' ');
}

/** ইংরেজি ছোট লেখায় Title Case (অ্যাপের TitleCase.ui) — বাক্যে নয় */
function titleCase(s: string) {
  if (s.length > 80) return s;
  let v = titleCache.get(s);
  if (v === undefined) {
    v = isSentence(s) ? s : words(s);
    titleCache.set(s, v);
  }
  return v;
}

/** অ্যাপের মতো: tr("Log in", "লগইন") */
export function tr(en: string, bn: string): string {
  return lang.value === 'bn' ? bn : titleCase(en);
}

/** বার্তা (ভুল/তথ্য) — ইংরেজিতে Title Case ছাড়া */
export function trPlain(en: string, bn: string): string {
  return lang.value === 'bn' ? bn : en;
}

const BN_DIGITS = '০১২৩৪৫৬৭৮৯';
/** ভাষা অনুযায়ী অঙ্ক (বাংলায় ০-৯) */
export function num(v: number | string | null | undefined): string {
  const s = v == null ? '' : String(v);
  return lang.value === 'bn' ? s.replace(/[0-9]/g, (d) => BN_DIGITS[+d]) : s;
}

/** বাংলা অঙ্ক → ইংরেজি (ইনপুটের লেখা পড়তে) */
export function toAsciiDigits(s: string): string {
  return s.replace(/[০-৯]/g, (d) => String(BN_DIGITS.indexOf(d)));
}
