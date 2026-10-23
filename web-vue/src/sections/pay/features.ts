// ফিচার সুইচ (backend/docs/FEATURES.md, PAYMENTS.md): সব বন্ধ অবস্থায় আসে, অ্যাডমিন থেকে চালু হয়।
//   pay_manual — মূল সুইচ (হাতে MFS পেমেন্ট); বাকিগুলো এর সাথে মিলিয়ে:
//   pay_chamber_advance, pay_delivery (+inv_pharmacy), pay_lab (+inv_lab), pay_ambulance (+inv_ambulance), pay_due
//   pay_bkash_pgw — শুধু Lifolo-র নিজের আয়ের bKash গেটওয়ে (একা চলে)
//   online_chamber — অনলাইন চেম্বারের ফি (chamber_fee) — নিজের চাবিতেই; সার্ভার অ্যাপয়েন্টমেন্টে পেমেন্ট দিলে নতুন পথ, নইলে আগের appt_pay_*
// রক্তের অংশে কখনো কোনো পেমেন্ট নয় — এখানে সেই চাবিই নেই।
import { featureOn } from '@/lib/features';
import type { BizKind } from './model';

export type PayKey = 'pay_chamber_advance' | 'pay_delivery' | 'pay_lab' | 'pay_ambulance' | 'pay_due';
const NEEDS: Record<PayKey, string[]> = {
  pay_chamber_advance: [],
  pay_delivery: ['inv_pharmacy'],
  pay_lab: ['inv_lab'],
  pay_ambulance: ['inv_ambulance'],
  pay_due: [],
};

/** মূল সুইচ */
export const payManualOn = () => featureOn('pay_manual');
/** একটা প্রবেশপথ চালু কি না: মূল সুইচ + নিজের চাবি + যা লাগে (reactive) */
export const payOn = (key: PayKey) => payManualOn() && featureOn(key) && NEEDS[key].every((k) => featureOn(k));
/** অনলাইন চেম্বারের ফি নতুন পথে (pay request; সার্ভার অ্যাপয়েন্টমেন্টে দিলে) — শুধু online_chamber লাগে */
export const onlineFeeOnPay = () => featureOn('online_chamber');
/** Lifolo-র নিজের আয়ের bKash গেটওয়ে */
export const pgwOn = () => featureOn('pay_bkash_pgw');

/** ব্যবসার "পেমেন্ট" পাতায় এই ধরন আসে কি না */
export function bizKindOn(kind: BizKind): boolean {
  // অনলাইন চেম্বারের ফি pay_manual ছাড়াও (সার্ভারের PayGate.anyOn)
  if (kind === 'chamber' && featureOn('online_chamber')) return true;
  if (!payManualOn()) return false;
  if (payOn('pay_due')) return true;
  switch (kind) {
    case 'chamber': return payOn('pay_chamber_advance');
    case 'pharmacy': return payOn('pay_delivery');
    case 'lab': return payOn('pay_lab');
    case 'ambulance': return payOn('pay_ambulance');
  }
}
/** কোনো ধরনই চালু কি না */
export const anyBizPayOn = () => (['chamber', 'pharmacy', 'lab', 'ambulance'] as BizKind[]).some(bizKindOn);
/** রোগীর "পেমেন্ট" ট্যাব (my_payments — সার্ভারের PayGate.anyOn): pay_manual বা অনলাইন চেম্বারের ফি */
export const patientPayOn = () => payManualOn() || featureOn('online_chamber');
