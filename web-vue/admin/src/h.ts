// পুরোনো প্যানেলের h()-এর মতো ছোট সাহায্যকারী, কিন্তু Vue-র VNode বানায় — যাতে পাতাগুলোর গঠন হুবহু একই থাকে।
// নিয়ম (আগের মতো): null/false প্রপ বাদ, true → খালি অ্যাট্রিবিউট, text → ভেতরের লেখা, onclick → ক্লিক।
// ব্যবহারকারীর লেখা সবসময় লেখা হিসেবে বসে (Vue টেক্সট নোড) — কখনো innerHTML নয়।
import { h as vh, type Component, type VNode, type VNodeArrayChildren } from 'vue';
import { ICONS } from './icons';

export type Kid = VNode | string | number | null | undefined | false | Kid[];
type Props = Record<string, unknown> | null | undefined;

function flat(kids: Kid[], out: (VNode | string)[] = []): (VNode | string)[] {
  for (const k of kids) {
    if (k == null || k === false || k === '') continue;
    if (Array.isArray(k)) flat(k, out);
    else out.push(typeof k === 'number' ? String(k) : k);
  }
  return out;
}

function props(p: Props): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  if (!p) return out;
  for (const [k, v] of Object.entries(p)) {
    if (v == null || v === false || k === 'text') continue;
    if (k.startsWith('on') && typeof v === 'function') out[`on${k[2].toUpperCase()}${k.slice(3)}`] = v;
    else out[k] = v === true ? '' : v;
  }
  return out;
}

/** HTML উপাদান (বা Vue কম্পোনেন্ট — তখন প্রপ যেমন আছে তেমনই যায়) */
export function h(tag: string, p?: Props, ...kids: Kid[]): VNode {
  const text = p && p.text != null && p.text !== false ? String(p.text) : null;
  const children = flat(text != null ? [text, ...kids] : kids);
  return vh(tag, props(p), children as VNodeArrayChildren);
}

/** কম্পোনেন্ট বসানো (প্রপ বদল ছাড়া) */
export const c = <P extends Record<string, unknown>>(comp: Component, p?: P & { key?: string | number }) => vh(comp, p ?? null);

/** SVG — Vue নিজেই svg-এর ভেতরে নামস্থান ঠিক করে */
export const s = h;

export function icon(name: string, cls?: string): VNode {
  return vh('svg', { class: cls || 'ic', viewBox: '0 0 960 960', 'aria-hidden': 'true' }, [vh('path', { d: ICONS[name] || '' })]);
}
