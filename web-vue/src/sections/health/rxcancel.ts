// প্রেসক্রিপশন বাতিলের কারণ (rx_cancel {id, reason, note?} — backend/docs/RX-STATE.md "Cancellation reason")।
// ডাক্তার বাছেন (RxCancelDialog), রোগী আর কেয়ারগিভার দেখেন (state.cancelReason / cancelNote, টাইমলাইন); অ্যাপের RxCancel (RxCancel.kt)।
// কারণ শুধু রোগীর নিজের দেখায় — যাচাইয়ের পাতা, ফার্মেসি বা পুশে কখনো নয় (নোটে চিকিৎসার কথা থাকতে পারে)।
import { tr } from '@/lib/i18n';

export type CancelReason = 'wrong_medicine' | 'wrong_patient' | 'replaced' | 'patient_reported' | 'other';
export const CANCEL_REASONS: CancelReason[] = ['wrong_medicine', 'wrong_patient', 'replaced', 'patient_reported', 'other'];
export const NOTE_MIN = 3;
export const NOTE_MAX = 200;

/** চাবি → লেখা; অজানা বা নেই (আগের release-এ বাতিল) → null */
export function cancelReasonText(key?: string | null): string | null {
  switch (key) {
    case 'wrong_medicine': return tr('Wrong medicine/dose', 'ভুল ওষুধ/মাত্রা'); // bn-ok
    case 'wrong_patient': return tr('Wrong patient', 'ভুল রোগী'); // bn-ok
    case 'replaced': return tr('Gave a new prescription', 'নতুন প্রেসক্রিপশন দিয়েছি'); // bn-ok
    case 'patient_reported': return tr('Patient reported a problem', 'রোগী জানিয়েছেন সমস্যা'); // bn-ok
    case 'other': return tr('Other reason', 'অন্য কারণ'); // bn-ok
    default: return null;
  }
}

/** "বাতিল করেছেন ডাক্তার — <কারণ>" (কারণ না থাকলে শুধু প্রথম অংশ) */
export function cancelledLine(key?: string | null): string {
  const base = tr('Cancelled by the doctor', 'বাতিল করেছেন ডাক্তার'); // bn-ok
  const why = cancelReasonText(key);
  return why ? `${base} — ${why}` : base;
}

/** নোট ঠিক আছে কি না: "অন্য কারণ"-এ ৩–২০০ অক্ষর লাগবে, বাকিগুলোয় ঐচ্ছিক (সর্বোচ্চ ২০০) */
export function noteOk(reason: CancelReason | null, note: string): boolean {
  const n = note.trim().length;
  if (!reason) return false;
  if (n > NOTE_MAX) return false;
  return reason !== 'other' || n >= NOTE_MIN;
}
