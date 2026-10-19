// "ডাক" (২.২৮.০)-এর তথ্যের ধরন আর ম্যাপের লিংক (কম্পোনেন্ট: AmbulanceCalls.vue)
import { areaLabel } from '@/lib/geo';
import type { Dest } from '@/sections/services/fare';

export interface DriverReq {
  id: string;
  status: 'open' | 'accepted' | 'arrived';
  createdAt: number;
  ambulanceId: string | null;
  km: number | null;
  kmApprox?: boolean;
  type: string | null;
  district: string;
  upazilaId: number | null;
  unionId?: number | null;
  pickup: string;
  note: string;
  called: boolean;
  patientName?: string;
  phone?: string;
  lat?: number;
  lng?: number;
  /** ভাড়ার অনুরোধ (২.৩০.০) */
  quote?: boolean;
  dest?: Dest | null;
  destLabel?: string;
  maxFare?: number | null;
  oxygen?: boolean;
  night?: boolean;
  suggestedFare?: number | null;
  myQuote?: { fare: number; at: number; expiresAt: number } | null;
  agreedFare?: number | null;
}

/** ম্যাপের লিংক: স্থানাঙ্ক থাকলে সেটা, নইলে "চেনা জায়গা, ইউনিয়ন, উপজেলা, জেলা, বাংলাদেশ" দিয়ে খোঁজা */
export function mapLink(r: Pick<DriverReq, 'lat' | 'lng' | 'pickup' | 'district' | 'upazilaId' | 'unionId'>): string {
  const q = r.lat != null && r.lng != null ? `${r.lat},${r.lng}` : [r.pickup, areaLabel(r.district, r.upazilaId, r.unionId), 'Bangladesh'].filter(Boolean).join(', ');
  return `https://www.google.com/maps/search/?api=1&query=${encodeURIComponent(q)}`;
}

