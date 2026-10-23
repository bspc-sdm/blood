import { toValue, type MaybeRefOrGetter } from 'vue';
import { useCall } from '@/lib/query';
import { forDoctor, type Actor, type RxAppt, type RxPatientsData, type RxTarget } from './model';

export type Link = (path: string, extra?: Record<string, string>) => string;

export function usePatients(actor: MaybeRefOrGetter<Actor>) {
  return useCall<RxPatientsData>('rx_patients', () => forDoctor(toValue(actor)));
}
export type PatientsQuery = ReturnType<typeof usePatients>;

/** appt: অ্যাপের অ্যাপয়েন্টমেন্ট (অনলাইন হলে ফির অবস্থা আর কল) */
export interface Row { key: string; t: RxTarget; icon: string; title: string; sub: string; tag: string | null; appt?: RxAppt }
export interface Group { key: string; title: string; rows: Row[]; empty?: string }
