import { isBangla, tr } from '@/lib/i18n';

export interface Hospital { id: string; nameEn: string; nameBn: string; type: string; district: string; upazilaId: number | null; unionId: number | null }
export const hospitalLabel = (h: Hospital) => (isBangla() && h.nameBn.trim() ? h.nameBn : h.nameEn);

export const HOSPITAL_TYPES = ['govt_mch', 'govt_specialized', 'govt_district', 'govt_uhc', 'govt_hospital', 'private_hospital', 'clinic', 'diagnostic'];
export function typeLabel(t: string) {
  switch (t) {
    case 'govt_mch': return tr('Govt. medical college hospital', 'সরকারি মেডিকেল কলেজ হাসপাতাল');
    case 'govt_specialized': return tr('Govt. specialized hospital', 'সরকারি বিশেষায়িত হাসপাতাল');
    case 'govt_district': return tr('District / Sadar hospital', 'জেলা / সদর হাসপাতাল');
    case 'govt_uhc': return tr('Upazila Health Complex', 'উপজেলা স্বাস্থ্য কমপ্লেক্স');
    case 'govt_hospital': return tr('Other govt. hospital', 'অন্যান্য সরকারি হাসপাতাল');
    case 'private_hospital': return tr('Private hospital', 'বেসরকারি হাসপাতাল');
    case 'clinic': return tr('Clinic', 'ক্লিনিক');
    default: return tr('Diagnostic centre', 'ডায়াগনস্টিক সেন্টার');
  }
}
