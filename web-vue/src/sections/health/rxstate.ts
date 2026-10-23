// প্রেসক্রিপশন এখন কোন অবস্থায় — সার্ভারের state (backend/docs/RX-STATE.md) থেকে লেখা, রং আর ট্যাব; অ্যাপের RxStateText (Prescriptions.kt)।
// নিয়ম সার্ভারে; এখানে শুধু চাবি → লেখা। পুরোনো সার্ভারে state নেই: তখন শুধু বাতিল/চালু দিয়ে আগের মতো।
import { num, tr } from '@/lib/i18n';
import { formatDate, formatDayOf } from '@/lib/format';
import type { Rx } from './rx';

export type RxTone = 'primary' | 'good' | 'info' | 'error' | 'neutral' | 'muted';
export interface RxStateMed { state: string; bought: number; total: number; pharmacyName?: string | null; mode?: string | null }
export interface RxStateLab { state: string; orders: number; reported: number; labName?: string | null; reviewed: boolean }
export interface RxStep { kind: string; at: number; name?: string; mode?: string; count?: number; ahead?: boolean }
export interface RxState {
  phase: 'active' | 'past';
  primary: string;
  tone: RxTone;
  cancelled: boolean;
  cancelledAt?: number | null;
  medicine: RxStateMed | null;
  lab: RxStateLab | null;
  followUpDate: number | null;
  lastActivityAt: number;
  /** কেন পূর্বের: cancelled | done | superseded | finished | course_end | stale (চলমান হলে null) — নতুন সার্ভারে */
  pastReason?: string | null;
  doneAt?: number | null;
  reopenedAt?: number | null;
  supersededBy?: { id: string; at: number } | null;
  courseEnd?: number | null;
  timeline?: RxStep[];
}

/** ট্যাব: চলমান / পূর্বের / সব */
export type RxTab = 'active' | 'past' | 'all';
export const RX_TABS: RxTab[] = ['active', 'past', 'all'];

/** চলমান না পূর্বের — পুরোনো সার্ভারে (state নেই) বাতিল হলে পূর্বের, নইলে চলমান */
export const phaseOf = (rx: Rx): 'active' | 'past' => rx.state?.phase ?? (rx.status === 'cancelled' ? 'past' : 'active');

export function inTab(rx: Rx, tab: RxTab): boolean {
  return tab === 'all' || phaseOf(rx) === tab;
}

export function tabLabel(tab: RxTab, n: number): string {
  const name = tab === 'active' ? tr('Ongoing', 'চলমান') : tab === 'past' ? tr('Previous', 'পূর্বের') : tr('All', 'সব'); // bn-ok
  return `${name} (${num(n)})`;
}

function medText(m: RxStateMed): string {
  switch (m.state) {
    case 'out': return tr('On the way', 'ডেলিভারির পথে'); // bn-ok
    case 'ready': return tr('Medicines ready — collect at the shop', 'ওষুধ তৈরি — দোকান থেকে নিন'); // bn-ok
    case 'accepted': return tr('Pharmacy accepted', 'ফার্মেসি গ্রহণ করেছে'); // bn-ok
    case 'sent': return tr('Sent to pharmacy — waiting for reply', 'ফার্মেসিতে পাঠানো — উত্তরের অপেক্ষা'); // bn-ok
    case 'running_out': return tr('Medicines running out', 'ওষুধ শেষ হয়ে আসছে'); // bn-ok
    case 'all_bought': return tr('All medicines bought', 'সব ওষুধ কেনা হয়েছে'); // bn-ok
    case 'declined': return tr("Pharmacy couldn't give — send to another", 'ফার্মেসি দিতে পারেনি — অন্য ফার্মেসিতে পাঠান'); // bn-ok
    case 'partial': return tr(`Partly bought (${m.bought}/${m.total})`, `আংশিক কেনা (${num(m.bought)}/${num(m.total)})`); // bn-ok
    default: return tr('Medicines not bought', 'ওষুধ কেনা হয়নি'); // bn-ok
  }
}

export function labText(l: RxStateLab): string {
  const base = (() => {
    switch (l.state) {
      case 'sent': return tr('Tests sent to the lab', 'ল্যাবে পাঠানো'); // bn-ok
      case 'received': return tr('Sample taken', 'নমুনা নেওয়া হয়েছে');
      case 'reported': return tr('Report ready', 'রিপোর্ট এসেছে');
      default: return tr('Tests pending', 'পরীক্ষা বাকি'); // bn-ok
    }
  })();
  const parts = [base];
  if (l.state !== 'reported' && l.reported > 0) parts.push(tr(`${l.reported}/${l.orders} reports in`, `${num(l.reported)}/${num(l.orders)}টা রিপোর্ট এসেছে`)); // bn-ok
  if (l.state === 'reported' && l.reviewed) parts.push(tr('Doctor checked', 'ডাক্তার দেখেছেন'));
  return parts.join(' · ');
}

export const doneText = () => tr('You marked it finished', 'আপনি শেষ করেছেন'); // bn-ok
export const supersededText = (at: number) => tr('Replaced by a newer prescription · ', 'নতুন প্রেসক্রিপশনে বদলেছে · ') + formatDayOf(at); // bn-ok
/** "শেষ হয়েছে" দেখানো যায় (চলমান, বাতিল নয়); "আবার চলমান করুন" — পূর্বের, বাতিল নয় */
export const canMarkDone = (rx: Rx) => !!rx.state && !rx.state.cancelled && rx.state.phase === 'active';
export const canReopen = (rx: Rx) => !!rx.state && !rx.state.cancelled && rx.state.phase === 'past';
export const followUpText = (d: number) => tr('Follow-up: ', 'ফলো-আপ: ') + formatDate(d); // bn-ok

/** কার্ডের রঙিন চিপের এক লাইন (অগ্রাধিকার সার্ভারের primary-তে) */
export function primaryText(rx: Rx): { text: string; tone: RxTone } | null {
  const s = rx.state;
  if (!s) return rx.status === 'cancelled' ? { text: tr('Cancelled by the doctor', 'বাতিল করেছেন ডাক্তার'), tone: 'muted' } : null; // bn-ok
  if (s.cancelled) return { text: tr('Cancelled by the doctor', 'বাতিল করেছেন ডাক্তার'), tone: 'muted' }; // bn-ok
  if (s.medicine && s.primary === s.medicine.state) return { text: medText(s.medicine), tone: s.tone };
  if (s.lab && s.primary === `lab_${s.lab.state}`) return { text: labText(s.lab), tone: s.tone };
  if (s.primary === 'follow_up' && s.followUpDate) return { text: followUpText(s.followUpDate), tone: s.tone };
  return { text: tr('Prescribed', 'লেখা হয়েছে'), tone: s.tone };
}

/** চিপের নিচের ছোট লাইনগুলো: পরীক্ষা (মূল লাইনে না গেলে), ফলো-আপ (মূল লাইনে না গেলে) */
export function secondaryLines(rx: Rx): [string, string][] {
  const s = rx.state;
  if (!s || s.cancelled) return [];
  const out: [string, string][] = [];
  // কেন পূর্বের: রোগী নিজে শেষ করেছেন / একই ডাক্তারের নতুন প্রেসক্রিপশন
  if (s.pastReason === 'done') out.push(['check_circle', doneText()]);
  if (s.pastReason === 'superseded' && s.supersededBy) out.push(['history', supersededText(s.supersededBy.at)]);
  if (s.lab && s.primary !== `lab_${s.lab.state}`) out.push(['list_alt', labText(s.lab)]);
  if (s.followUpDate && s.primary !== 'follow_up') out.push(['calendar_month', followUpText(s.followUpDate)]);
  return out;
}

/** টাইমলাইনের একটা ধাপ */
export function stepText(st: RxStep): string {
  const at = (n?: string) => (n ? ` — ${n}` : '');
  switch (st.kind) {
    case 'written': return tr('Prescription written', 'প্রেসক্রিপশন লেখা হয়েছে'); // bn-ok
    case 'pharmacy_sent': return (st.mode === 'online' ? tr('Online order sent', 'অনলাইনে অর্ডার পাঠানো') : tr('Sent to pharmacy', 'ফার্মেসিতে পাঠানো')) + at(st.name); // bn-ok
    case 'pharmacy_accepted': return tr('Pharmacy accepted', 'ফার্মেসি গ্রহণ করেছে') + at(st.name); // bn-ok
    case 'pharmacy_ready': return tr('Medicines ready at the shop', 'দোকানে ওষুধ তৈরি') + at(st.name); // bn-ok
    case 'pharmacy_out': return tr('Out for delivery', 'ডেলিভারির পথে') + at(st.name); // bn-ok
    case 'pharmacy_delivered': return tr('Delivered', 'পৌঁছে দেওয়া হয়েছে') + at(st.name); // bn-ok
    case 'pharmacy_cancelled': return tr("Pharmacy couldn't give", 'ফার্মেসি দিতে পারেনি') + at(st.name); // bn-ok
    case 'pharmacy_withdrawn': return tr('You took it back', 'ফিরিয়ে নিয়েছেন') + at(st.name);
    case 'bought': return tr(`Bought ${st.count ?? 0} medicine(s)`, `${num(st.count ?? 0)}টা ওষুধ কেনা হয়েছে`) + at(st.name); // bn-ok
    case 'lab_sent': return tr('Tests sent to the lab', 'ল্যাবে পরীক্ষা পাঠানো') + at(st.name); // bn-ok
    case 'lab_received': return tr('Sample taken', 'নমুনা নেওয়া হয়েছে') + at(st.name);
    case 'lab_reported': return tr('Report ready', 'রিপোর্ট এসেছে') + at(st.name);
    case 'lab_cancelled': return tr("Lab couldn't do it", 'ল্যাব করতে পারেনি') + at(st.name); // bn-ok
    case 'reviewed': return tr('Doctor checked the report', 'ডাক্তার রিপোর্ট দেখেছেন'); // bn-ok
    case 'cancelled': return tr('Cancelled by the doctor', 'বাতিল করেছেন ডাক্তার'); // bn-ok
    case 'superseded': return tr('Replaced by a newer prescription', 'নতুন প্রেসক্রিপশনে বদলেছে'); // bn-ok
    case 'marked_done': return doneText();
    case 'reopened': return tr('Made ongoing again', 'আবার চলমান করেছেন'); // bn-ok
    case 'follow_up': return st.ahead ? tr('Follow-up visit', 'ফলো-আপে দেখানোর দিন') : tr('Follow-up day', 'ফলো-আপের দিন'); // bn-ok
    default: return st.kind;
  }
}

/** ধাপের তারিখ: ফলো-আপ ঢাকার দিন (UTC মধ্যরাত), বাকিগুলো আসল সময় */
export const stepDate = (st: RxStep) => (st.kind === 'follow_up' ? formatDate(st.at) : formatDayOf(st.at));
