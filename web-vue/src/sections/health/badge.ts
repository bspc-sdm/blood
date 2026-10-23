// পেশাদার ব্যাজ: কোন কাগজ লাগে, অবস্থার লেখা — অ্যাপের BadgeApplyDialog, Verify.kt
import { tr } from '@/lib/i18n';
import { badgeLabel } from './common';
import { practiceDocs, systemTag } from '@/lib/practice';

export interface DocSpec { type: string; label: string; hint: string; required: boolean }

/** কোন কাগজ লাগে — সার্ভারের lib/verify.js DOCS.badge-এর সাথে মেলানো */
/** system: ডাক্তারের চিকিৎসা পদ্ধতি (practice_systems) — অ-এলোপ্যাথি আর নতুন পেশাজীবীর কাগজ lib/practice.ts-এ */
export function badgeDocs(type: string, system?: unknown): DocSpec[] {
  const own = practiceDocs(type, system);
  if (own) return own;
  const idcard = { type: 'idcard', label: tr('Workplace ID card', 'কর্মস্থলের পরিচয়পত্র'), hint: tr('If you have it', 'থাকলে দিন'), required: false };
  if (type === 'doctor') {
    return [{ type: 'cert', label: tr('BMDC registration certificate', 'BMDC নিবন্ধন সনদ'), hint: tr('Optional — the number is checked online', 'থাকলে দিন — নম্বর অনলাইনে মিলিয়ে দেখা হয়'), required: false }, idcard];
  }
  if (type === 'nurse') {
    return [{ type: 'cert', label: tr('BNMC registration certificate', 'নার্সিং ও মিডওয়াইফারি কাউন্সিলের (BNMC) নিবন্ধন সনদ'), hint: tr('Number and name readable', 'নম্বর আর নাম পড়া যায়'), required: true }, idcard];
  }
  return [{ type: 'cert', label: tr('Certificate or degree', 'পেশার সনদ বা ডিগ্রির কাগজ'), hint: tr('Name and institution readable', 'নাম আর প্রতিষ্ঠান পড়া যায়'), required: true }, idcard];
}
export function missingDocs(specs: DocSpec[], photos: Record<string, string>): string | null {
  const miss = specs.filter((d) => d.required && !photos[d.type]);
  return miss.length ? tr('Add photos of: ', 'এই কাগজের ছবি দিন: ') + miss.map((d) => d.label).join(', ') : null;
}

export function statusLine(b: { status: string; type: string; changePending?: boolean; system?: unknown }): { text: string; tone?: 'good' | 'error' | 'info' } {
  if (b.status === 'approved') return { text: badgeLabel(b.type) + (systemTag(b.type, b.system) ? ' · ' + systemTag(b.type, b.system) : '') + (b.changePending ? tr(' · correction under review', ' · সংশোধন যাচাই চলছে') : ''), tone: 'good' };
  if (b.status === 'pending') return { text: tr('Under review', 'যাচাই চলছে'), tone: 'info' };
  if (b.status === 'rejected') return { text: tr('Not approved. Tap to apply again.', 'অনুমোদন হয়নি। আবার আবেদন করুন।'), tone: 'error' };
  return { text: b.status };
}
