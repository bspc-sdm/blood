// ডাক্তার/সহকারীর প্রেসক্রিপশনের ডেটা আর লেখা — সার্ভারের server/lib/prescriptions.js ও rxnet.js-এর আকারে,
// লেখা অ্যাপের Prescriptions.kt (RxText) আর RxNet.kt (RxNetText) থেকে।
import { useQueryClient } from '@tanstack/react-query';
import { num, tr } from '@/lib/i18n';
import { formatDate, todayUtcMidnight } from '@/lib/format';
import { genderLabel, type Rx, type RxMed } from '../../health/Prescriptions';

export type { Rx, RxMed };
export { genderLabel };

/** কে, কার হয়ে: ডাক্তার নিজে (doctorUid = null) বা সহকারী (ডাক্তারের uid) */
export interface Actor {
  role: 'doctor' | 'assistant';
  /** সহকারী হলে ডাক্তারের uid — প্রতিটা কাজে doctorUid হিসেবে যায় */
  doctorUid: string | null;
  doctorName: string;
}
export const forDoctor = (a: Actor): Record<string, unknown> => (a.role === 'assistant' && a.doctorUid ? { doctorUid: a.doctorUid } : {});

export interface RxMe { name: string; degrees: string; chambers: string[] }
export interface RxPatient { id: string; name: string; age: number | null; gender: string; phone: string; linked: boolean; createdAt: number; lastRxAt: number }
export interface RxAppt { id: string; name: string; date: number; serial: number | null; status: string; chamber: string }
export interface RxAppPatient { appointmentId: string; name: string; count: number; last: number }
export interface RxPatientsData { role: string; patients: RxPatient[]; appointments: RxAppt[]; appPatients: RxAppPatient[] }
export interface DoctorRx extends Rx { linked?: boolean }

/** যাঁর জন্য লেখা: অ্যাপের অ্যাপয়েন্টমেন্ট অথবা এন্ট্রি করা রোগী (অ্যাপের RxTarget) */
export interface RxTarget { appointmentId?: string; rxPatientId?: string; name: string; age?: number | null; gender?: string }
export const targetKey = (t: RxTarget) => (t.rxPatientId ? 'p:' + t.rxPatientId : 'a:' + t.appointmentId);
export const targetBody = (t: RxTarget) => (t.rxPatientId ? { rxPatientId: t.rxPatientId } : { appointmentId: t.appointmentId });
export const targetSearch = (t: RxTarget): Record<string, string> => (t.rxPatientId ? { patient: t.rxPatientId } : { appointment: t.appointmentId ?? '' });

/** URL-এর ?patient= / ?appointment= থেকে রোগী (তালিকায় পেলে নাম-বয়সসহ) */
export function findTarget(sp: URLSearchParams, d: RxPatientsData | undefined): RxTarget | null {
  const pid = sp.get('patient');
  const aid = sp.get('appointment');
  if (pid) {
    const p = d?.patients.find((x) => x.id === pid);
    return { rxPatientId: pid, name: p?.name ?? '', age: p?.age ?? null, gender: p?.gender ?? '' };
  }
  if (aid) {
    const a = d?.appointments.find((x) => x.id === aid);
    const h = d?.appPatients.find((x) => x.appointmentId === aid);
    return { appointmentId: aid, name: a?.name || h?.name || '' };
  }
  return null;
}

export interface Drug { i: number; name: string; generic: string; form: string; company: string }
export interface LabTest { code: string; name: string; bn?: string; aliases?: string[]; hidden?: boolean }

export interface RxReview {
  id: string; rxId: string; labOrderId: string; doctorUid: string; doctorName: string; patientName: string; labName: string; tests: string[];
  status: string; fee: string; noFee: boolean; payNumber: string; payMethods: string[]; method: string; trxId?: string; rejectNote: string;
  comment: string; newRxId: string | null; createdAt: number; updatedAt: number;
}
export interface ReviewSettings { enabled: boolean; noFee: boolean; fee: string; payNumber: string; payMethods: string[] }
export interface LabResult { test: string; result: string; unit: string; range: string; flag: string; group?: string; code?: string; key?: string; long?: boolean }
export interface LabOrder {
  id: string; rxId: string; code: string; labName: string; patientName: string; patientAge: number | null; patientGender: string; doctorName: string;
  tests: string[]; status: string; createdAt: number; report: { items: LabResult[]; note: string; reportedAt: number; photoCount: number; photos?: string[] } | null;
}
export type LabHistory = Record<string, { result: string; flag: string; at: number }[]>;
/**
 * ডাক্তারের দিক থেকে ল্যাব অর্ডার (rx_patient_history / rx_get-এর labOrders, সার্ভারের doctorLabView)।
 * অবস্থা সবসময়; report শুধু open হলে (রিপোর্ট দেখার টাকা মিলেছে বা "ফি চাই না") — নইলে null।
 */
export interface DoctorLab {
  id: string; rxId: string; labName: string; tests: string[]; status: string; createdAt: number; reportedAt: number;
  review: string | null; open: boolean; report: LabOrder['report']; comment: string;
}

// ---------- লেখা (অ্যাপের RxText / RxNetText) ----------
export const MAX_ASSISTANTS = 5; // সার্ভারের MAX_ASSISTANTS
export const MAX_MEDS = 25;
export const FINDINGS = ['bp', 'pulse', 'temp', 'weight', 'spo2'] as const;
export function findingLabel(k: string) {
  switch (k) {
    case 'bp': return tr('BP', 'প্রেসার');
    case 'pulse': return tr('Pulse', 'পালস');
    case 'temp': return tr('Temp', 'তাপমাত্রা');
    case 'weight': return tr('Weight', 'ওজন');
    case 'spo2': return 'SpO₂';
    default: return tr('Other', 'অন্যান্য');
  }
}
export const timingLabel = (t?: string) => (t === 'before' ? tr('Before food', 'খাবারের আগে') : t === 'after' ? tr('After food', 'খাবারের পরে') : '');
export const DOSES = ['1+0+1', '1+0+0', '0+0+1', '1+1+1', '0+1+0', '1+1+1+1'];
export const DAYS = [3, 5, 7, 10, 14, 30];
export const FOLLOW = [3, 7, 15, 30];
export const COMMON_TESTS = ['CBC', 'RBS', 'FBS', 'HbA1c', 'Lipid profile', 'S. Creatinine', 'SGPT', 'Urine R/E', 'X-ray chest P/A', 'ECG', 'USG of whole abdomen', 'TSH'];
export const PAY_METHODS = ['bkash', 'nagad', 'rocket'];
export const payLabel = (m: string) => ({ bkash: 'bKash', nagad: 'Nagad', rocket: 'Rocket' })[m] ?? m;
export function reviewStatus(s: string) {
  switch (s) {
    case 'unpaid': return tr('Not paid', 'টাকা দেওয়া হয়নি');
    case 'claimed': return tr('Payment sent — doctor is checking', 'টাকা পাঠিয়েছেন — ডাক্তার মিলিয়ে দেখছেন');
    case 'paid': return tr('Doctor is checking the report', 'ডাক্তার রিপোর্ট দেখছেন');
    case 'done': return tr('Doctor checked', 'ডাক্তার দেখেছেন');
    default: return s;
  }
}
/** ল্যাব অর্ডারের অবস্থা (রোগীর পাতার labStatus-এর মতো, অ্যাপের RxNetText.labStatus) */
export function labStatus(s: string) {
  switch (s) {
    case 'sent': return tr('Sent — waiting', 'পাঠানো হয়েছে — অপেক্ষায়');
    case 'received': return tr('Sample taken', 'নমুনা নেওয়া হয়েছে');
    case 'reported': return tr('Report ready', 'রিপোর্ট এসেছে');
    case 'cancelled': return tr("Can't do", 'করতে পারবে না');
    default: return s;
  }
}
/** রিপোর্ট এসেছে কিন্তু ফল বন্ধ — কেন, কী করলে খুলবে (অ্যাপের RxNetText.labLocked) */
export function labLocked(review: string | null) {
  switch (review) {
    case 'unpaid': return tr('You can see it when the patient asks you to check the report', 'রোগী রিপোর্ট দেখানোর অনুরোধ করলে দেখা যাবে');
    case 'claimed': return tr('The patient sent the fee — confirm it in Reports to see the results', 'রোগী টাকা পাঠিয়েছেন — "রিপোর্ট" থেকে টাকা মিলিয়ে নিলে দেখা যাবে');
    default: return tr('Online report review was off — see the report with the patient', 'অনলাইনে রিপোর্ট দেখা বন্ধ ছিল — রিপোর্ট রোগীর কাছ থেকে দেখে নিন');
  }
}
export const flagLabel = (f: string) => (f === 'high' ? tr('High', 'বেশি') : f === 'low' ? tr('Low', 'কম') : f === 'abnormal' ? tr('Abnormal', 'অস্বাভাবিক') : '');

export const ageText = (age: number | null | undefined) => (age != null ? tr(`${age} yrs`, `${num(age)} বছর`) : '');
export const dayText = (d: number) => (d === todayUtcMidnight() ? tr('Today', 'আজ') : d === todayUtcMidnight() + 86400000 ? tr('Tomorrow', 'আগামীকাল') : formatDate(d));
export const deletedName = () => tr('Deleted account', 'মুছে ফেলা অ্যাকাউন্ট');
export const statusTag = (s: string) => (s === 'cancelled' ? tr('Cancelled', 'বাতিল') : tr('Active', 'চালু'));

/** লেখার পর বা বাতিলের পর পুরোনো তালিকা বাদ */
export function useRxInvalidate() {
  const qc = useQueryClient();
  return () =>
    qc.invalidateQueries({ predicate: (q) => ['rx_patients', 'rx_patient_history', 'rx_doctor_list', 'rx_get', 'reviews_doctor'].includes(String(q.queryKey[0])) });
}

/** কেয়ারগিভার মোড চালু থাকলেও ডাক্তারের নিজের কাজ (রোগীর uid নয়) — api.ts asUid না বসায় */
export const SELF = { asUid: undefined } as Record<string, unknown>;
