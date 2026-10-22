// অ্যাকাউন্টের অবস্থা (profile_get-এর `account`): চালু, সাময়িক বন্ধ (account_deactivate), মোছার অপেক্ষায় (delete_account, ৩০ দিন)।
// সাময়িক বন্ধ থাকলে সার্ভার বাকি সব কাজে 423 `account_deactivated` দেয় — তখন ওয়েব পুরোটা আটকে "আবার চালু করুন" দেখায়।
export type AccountState = 'active' | 'deactivated' | 'deleting';

export interface AccountInfo {
  state: AccountState;
  deactivatedAt?: number;
  deletionRequestedAt?: number;
  deletesAt?: number;
}

/** মোছার পর যতদিন ফেরানো যায় (সার্ভারের GRACE_MS) */
export const DELETE_GRACE_DAYS = 30;
const DAY_MS = 24 * 3600 * 1000;

/** আজ মুছলে কবে চিরতরে মুছবে (মিলিসেকেন্ড) */
export function deleteDateFrom(now: number): number {
  return now + DELETE_GRACE_DAYS * DAY_MS;
}

const millis = (v: unknown): number | undefined => {
  if (typeof v === 'number' && Number.isFinite(v) && v > 0) return v;
  if (typeof v === 'string' && v.trim()) {
    const n = /^\d+$/.test(v) ? Number(v) : Date.parse(v);
    return Number.isFinite(n) && n > 0 ? n : undefined;
  }
  return undefined;
};

/** সার্ভারের `account` → AccountInfo; না থাকলে/অচেনা হলে null (মানে কিছু জানা নেই, চালু ধরে নেওয়া) */
export function parseAccount(x: unknown): AccountInfo | null {
  if (!x || typeof x !== 'object') return null;
  const o = x as Record<string, unknown>;
  const state = o.state;
  if (state !== 'active' && state !== 'deactivated' && state !== 'deleting') return null;
  const out: AccountInfo = { state };
  const d = millis(o.deactivatedAt);
  const r = millis(o.deletionRequestedAt);
  const del = millis(o.deletesAt);
  if (d) out.deactivatedAt = d;
  if (r) out.deletionRequestedAt = r;
  if (del) out.deletesAt = del;
  return out;
}
