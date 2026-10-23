// প্রেসক্রিপশনের ধরন আর লেখা (React-এর Prescriptions.tsx-এর উপরের অংশ, হুবহু) — অ্যাপের RxText
import { num, tr } from '@/lib/i18n';
import { PRACTICE_SPEC_LABEL, type RxRules } from '@/lib/practice';

export interface RxMed { name: string; form?: string; strength?: string; dose?: string; timing?: string; days?: number; note?: string }
export interface Rx {
  id: string; code: string; status: string; createdAt: number; doctorUid: string;
  doctor: {
    name?: string; degrees?: string; regNo?: string; specialties?: string[]; chamber?: { name?: string; address?: string; phone?: string } | null;
    /** practice: চিকিৎসা পদ্ধতি আর পেশা (physio/psychologist) — পুরোনো সার্ভারে নেই */
    system?: string; type?: string;
  };
  /** practice: লেখকের পেশা আর প্রেসক্রিপশনের নিয়ম (সার্ভারের Practice.rxRules) — পুরোনো সার্ভারে নেই */
  doctorType?: string; rxRules?: RxRules | null;
  patient: { name?: string; age?: number | null; gender?: string };
  complaints: string; findings: Record<string, string>; diagnosis: string; medicines: RxMed[]; tests: string[]; testPlace: string; testPlaceId?: string | null;
  advice: string; followUpDate: number | null; followUpBookDate: number | null; followUpNote: string; chamberIndex: number | null;
}

// ---------- লেখা (অ্যাপের RxText) ----------
export const FINDINGS = ['bp', 'pulse', 'temp', 'weight', 'spo2', 'other'];
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
const timingLabel = (t?: string) => (t === 'before' ? tr('Before food', 'খাবারের আগে') : t === 'after' ? tr('After food', 'খাবারের পরে') : '');
export const genderLabel = (g?: string) => (g === 'male' ? tr('Male', 'পুরুষ') : g === 'female' ? tr('Female', 'মহিলা') : '');
function formShort(f?: string) {
  const l = (f ?? '').toLowerCase();
  if (!l.trim()) return '';
  if (l.includes('tablet')) return 'Tab.';
  if (l.includes('capsule')) return 'Cap.';
  if (l.includes('syrup') || l.includes('suspension') || l.includes('oral solution')) return 'Syr.';
  if (l.includes('injection') || l.includes('infusion')) return 'Inj.';
  if (l.includes('drop')) return 'Drop';
  if (l.includes('cream')) return 'Cream';
  if (l.includes('ointment')) return 'Oint.';
  if (l.includes('inhal')) return 'Inh.';
  return f ?? '';
}
export const medTitle = (m: RxMed) => [formShort(m.form), m.name].filter((x) => x && x.trim()).join(' ') + (m.strength ? ` (${m.strength})` : '');
export const medHowTo = (m: RxMed) =>
  [num(m.dose ?? ''), timingLabel(m.timing), m.days && m.days > 0 ? tr(`${m.days} days`, `${num(m.days)} দিন`) : tr('Continue', 'চলবে')].filter((x) => x.trim()).join(' · ');
export const patientLine = (rx: Rx) =>
  [rx.patient?.name, rx.patient?.age != null ? tr(`${rx.patient.age} yrs`, `${num(rx.patient.age)} বছর`) : null, genderLabel(rx.patient?.gender) || null].filter(Boolean).join(' · ');

const SPECIALTIES: Record<string, [string, string]> = {
  medicine: ['Medicine', 'মেডিসিন'], pediatrics: ['Child (Pediatrics)', 'শিশু রোগ'], gynecology: ['Gynae & Obstetrics', 'গাইনি ও প্রসূতি'],
  cardiology: ['Heart (Cardiology)', 'হৃদরোগ'], orthopedics: ['Bone (Orthopedics)', 'হাড় ও জোড়া'], ent: ['ENT', 'নাক-কান-গলা'], eye: ['Eye', 'চক্ষু'],
  dermatology: ['Skin (Dermatology)', 'চর্ম ও যৌন'], neurology: ['Neurology', 'স্নায়ুরোগ'], psychiatry: ['Psychiatry', 'মানসিক রোগ'], surgery: ['Surgery', 'সার্জারি'],
  nephrology: ['Kidney (Nephrology)', 'কিডনি রোগ'], urology: ['Urology', 'মূত্ররোগ'], gastro: ['Gastroenterology', 'পরিপাকতন্ত্র ও লিভার'], oncology: ['Cancer (Oncology)', 'ক্যান্সার'],
  dental: ['Dental', 'দন্ত'], endocrinology: ['Diabetes & Hormone', 'ডায়াবেটিস ও হরমোন'], chest: ['Chest & Lung', 'বক্ষব্যাধি'], physical_medicine: ['Physical medicine', 'ফিজিক্যাল মেডিসিন'],
  general: ['General practitioner', 'সাধারণ চিকিৎসক'],
};
export const specialtyLabel = (c: string) => (SPECIALTIES[c] ? tr(SPECIALTIES[c][0], SPECIALTIES[c][1]) : PRACTICE_SPEC_LABEL[c] ? tr(PRACTICE_SPEC_LABEL[c][0], PRACTICE_SPEC_LABEL[c][1]) : c);


// ---------- সেবার পাতা থেকে পাঠানো (RxActivity-র ধরন) ----------
export interface LastHome { address: string; phone: string }
/** সেবার তালিকার প্যাথলজি (services/data-র Lab) — পাতা থেকে পাঠানোর সময় */
export interface PlaceLab {
  id: string; name: string; phone: string; address: string; district: string; upazilaId: number | null; facilityId: string | null;
  homeCollection: boolean; hours: string; prices?: { homeFee: number | null } | null;
}
/** সেবার তালিকার ফার্মেসি — যতটুকু লাগে */
export interface PlacePharmacy { id: string; name: string; district: string }

/** কোড চার অক্ষর করে ভাগ: "AB23CD45EF" → "AB23 CD45 EF" (দোকানে পড়ে লেখা সহজ) */
export const groupCode = (code: string) => (code.match(/.{1,4}/g) ?? []).join(' ');
