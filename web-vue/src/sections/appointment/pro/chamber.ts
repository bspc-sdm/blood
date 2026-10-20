// চেম্বার সেটআপের হিসাব: খালি চেম্বার/বাসায় সেবা, সার্ভারে পাঠানোর রূপ
import type { Chamber, ChamberTime, HomeVisit } from '../model';

export const TERMS_URL = 'https://lifolo.com/terms';
export const emptyHome = (): HomeVisit => ({ enabled: false, radiusKm: 5, upazilaIds: [], services: [], schedule: '', fee: '', phone: '', noFee: false, times: [] });
export const newSlot = (): ChamberTime => ({ days: [], start: '', end: '' });
export type Ed = Chamber & { key: number };
let keySeq = 1;
export const nextKey = () => keySeq++;

export function chamberJson(c: Chamber) {
  return {
    hospitalId: c.hospitalId,
    room: c.room,
    schedule: c.schedule,
    ...(c.custom ? { placeName: c.hospitalName, district: c.district, address: c.address, ...(c.upazilaId != null ? { upazilaId: c.upazilaId } : {}) } : {}),
    fee: c.fee,
    noFee: c.noFee,
    appointments: c.appointments,
    serialPhone: c.serialPhone,
    days: c.days,
    start: c.start,
    end: c.end,
    ...(c.times.length > 1 ? { times: c.times } : {}),
    patientMin: c.patientMin,
  };
}
export function homeJson(h: HomeVisit) {
  return {
    enabled: h.enabled, termsAccepted: h.enabled, radiusKm: h.radiusKm, upazilaIds: h.upazilaIds, services: h.services,
    schedule: h.schedule, fee: h.fee, noFee: h.noFee, phone: h.phone, times: h.times,
  };
}

export function blankChamber(district: string, custom: boolean): Chamber {
  return {
    hospitalId: null, hospitalName: '', hospitalNameBn: '', district, upazilaId: null, unionId: null, room: '', schedule: '', fee: '',
    appointments: true, serialPhone: '', days: [], start: '', end: '', noFee: false, custom, address: '', times: [], patientMin: null,
  };
}
