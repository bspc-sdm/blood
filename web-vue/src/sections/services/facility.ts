// "কোথায় যাবেন" (২.৩১.০): হাসপাতাল/ক্লিনিকের তালিকা থেকে বাছা, নয়তো "অন্য জায়গা লিখুন"।
// তালিকা সার্ভারের amb_facilities (DGHS Facility Registry, ডায়াগনস্টিক বাদ; lib/ambplace.js): রোগীর জেলারটা আগে
// (জায়গা থাকলে কাছেরটা আগে), তারপর সারা দেশের বড় হাসপাতাল; খোঁজা সারা দেশে, বাংলা/ইংরেজিতে ("য়" একরকম করে)।
// মালিকের ভাড়ার তালিকার হাসপাতালের সারিও এখান থেকে (fare.tsx)।
import { tr, isBangla } from '@/lib/i18n';
import { upazila, districtLabel } from '@/lib/geo';

/** সার্ভারের ছোট রূপ: [id, ইংরেজি, বাংলা, ধরন, জেলা, উপজেলা] */
export type FacilityRow = [string, string, string, string, string, number | null];
export interface Facility { facilityId: string; name: string; upazilaId: number | null; district: string }

export const nameOf = (r: FacilityRow) => (isBangla() ? r[2] || r[1] : r[1]);
const TYPE_LABEL: Record<string, [string, string]> = {
  govt_mch: ['Medical college', 'মেডিকেল কলেজ'], govt_specialized: ['Specialised (govt)', 'বিশেষায়িত (সরকারি)'],
  govt_district: ['District hospital', 'জেলা হাসপাতাল'], govt_uhc: ['Upazila health complex', 'উপজেলা স্বাস্থ্য কমপ্লেক্স'],
  govt_hospital: ['Govt hospital', 'সরকারি হাসপাতাল'], private_hospital: ['Private hospital', 'বেসরকারি হাসপাতাল'], clinic: ['Clinic', 'ক্লিনিক'],
};
export function subOf(r: FacilityRow): string {
  const t = TYPE_LABEL[r[3]];
  const u = upazila(r[5]);
  return [t ? tr(t[0], t[1]) : '', u ? tr(u.en, u.bn) : '', districtLabel(r[4])].filter(Boolean).join(' · ');
}
export const toFacility = (r: FacilityRow): Facility => ({ facilityId: r[0], name: nameOf(r), upazilaId: r[5], district: r[4] });

