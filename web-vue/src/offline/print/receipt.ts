// রসিদের চেহারা (CONTRACT "Receipt look"): এক মডেল — HTML (A4/ব্রাউজার) আর থার্মালের ছবি দুটোই এটা থেকে আঁকে।
// মাথায় ব্যবসার নাম, ঠিকানা, ফোন, ছোট Lifolo; তারপর লাইন, মোট, পরিশোধ/বাকি/মাধ্যম; QR আর "lifolo.com/v/<code> — রসিদ যাচাই করুন";
// অঙ্ক ডিফল্ট বাংলা (ইংরেজি বাছাই করা যায়); বাতিল রসিদে "বাতিল" ছাপ।
import { bnDigits, formatTaka } from '../money';
import { R } from '../text';
import type { Invoice } from '../types';

export interface ReceiptRow {
  label: string;
  value: string;
  strong?: boolean;
}
export interface ReceiptLine {
  name: string;
  detail: string;
  amount: string;
}
export interface ReceiptModel {
  bizName: string;
  bizAddress: string;
  bizPhone: string;
  title: string;
  meta: ReceiptRow[];
  lines: ReceiptLine[];
  totals: ReceiptRow[];
  payments: ReceiptRow[];
  void: boolean;
  voidReason: string;
  offline: boolean;
  qrText: string;
  verifyText: string;
  /** অফলাইনে বানানো, এখনো সিঙ্ক হয়নি: QR-এর নিচে "ইন্টারনেট এলে যাচাই হবে" */
  offlineVerify: string;
  demo: boolean;
  voidLabel: string;
  demoLabel: string;
  thanks: string;
  notVat: string;
}

const pad2 = (n: number) => String(n).padStart(2, '0');

/** ঢাকার সময়ে "23/10/2026, 3:45 PM" */
export function receiptDate(ms: number): string {
  const d = new Date(ms + 6 * 3600_000); // Asia/Dhaka (UTC+6, ডেলাইট নেই)
  const h = d.getUTCHours();
  const h12 = h % 12 || 12;
  return `${pad2(d.getUTCDate())}/${pad2(d.getUTCMonth() + 1)}/${d.getUTCFullYear()}, ${h12}:${pad2(d.getUTCMinutes())} ${h < 12 ? 'AM' : 'PM'}`;
}

function qtyText(q: number): string {
  return Number.isInteger(q) ? String(q) : String(Math.round(q * 1000) / 1000);
}

export function receiptModel(inv: Invoice, englishDigits = false): ReceiptModel {
  const dg = englishDigits ? (s: string) => s : bnDigits;
  const tk = (v: number) => dg(formatTaka(v));
  // রসিদ নং সবসময় ইংরেজি অঙ্কে (খোঁজা, ফোনে বলা, যাচাই — একই লেখা)
  const latin = (s: string) => s.replace(/[০-৯]/g, (d) => String('০১২৩৪৫৬৭৮৯'.indexOf(d)));
  const meta: ReceiptRow[] = [{ label: R.no, value: latin(inv.no || inv.localId || '') }];
  // সার্ভার নম্বর বদলালে আগে ছাপা নম্বরও দেখানো (দুটোতেই যাচাই হয়)
  if (inv.deviceNo && inv.deviceNo !== inv.no) meta.push({ label: R.printedNo, value: latin(inv.deviceNo) });
  meta.push({ label: R.date, value: dg(receiptDate(inv.issuedAt)) });
  // গ্রাহকের ফোন কখনো ছাপা হয় না (নিরাপত্তা পর্যালোচনা I6) — নাম দিলে শুধু নাম
  if (inv.customer?.name) meta.push({ label: R.name, value: inv.customer.name });

  const lines = inv.lines.map((l) => ({
    name: l.name,
    detail: dg(`${qtyText(l.qty)}${l.unit ? ' ' + l.unit : ''} × ${formatTaka(l.price)}${l.disc ? ` − ${formatTaka(l.disc)}` : ''}`),
    amount: tk(l.amount),
  }));

  const totals: ReceiptRow[] = [];
  const hasExtra = inv.disc > 0 || inv.fees.length > 0;
  if (hasExtra) totals.push({ label: R.subtotal, value: tk(inv.subtotal) });
  if (inv.disc > 0) totals.push({ label: R.disc, value: '−' + tk(inv.disc) });
  for (const f of inv.fees) totals.push({ label: f.label, value: tk(f.amount) });
  totals.push({ label: R.total, value: tk(inv.total), strong: true });

  const payments: ReceiptRow[] = inv.payments.map((p) => ({ label: `${R.paid} (${R.method[p.method] ?? p.method})`, value: tk(p.amount) }));
  if (!inv.payments.length) payments.push({ label: R.paid, value: tk(inv.paid) });
  if (inv.due > 0) payments.push({ label: R.due, value: tk(inv.due), strong: true });

  const code = inv.verifyCode || '';
  return {
    bizName: inv.bizName || 'Lifolo',
    bizAddress: inv.bizAddress || '',
    bizPhone: inv.bizPhone ? dg(inv.bizPhone) : '',
    title: R.kind[inv.kind] ?? '',
    meta,
    lines,
    totals,
    payments,
    void: inv.status === 'void',
    voidReason: inv.voidReason || '',
    offline: !!inv.offline && !inv.id,
    offlineVerify: inv.offline && !inv.id ? R.offlineVerify : '',
    demo: !!inv.demo || /^DEMO/.test(code),
    voidLabel: R.void,
    demoLabel: R.demo,
    qrText: inv.verifyUrl || (code ? `https://lifolo.com/v/${code}` : ''),
    verifyText: code ? `lifolo.com/v/${code} — ${R.verify}` : '',
    thanks: R.thanks,
    notVat: R.notVat,
  };
}
