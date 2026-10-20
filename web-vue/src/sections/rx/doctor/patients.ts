import { toValue, type MaybeRefOrGetter } from 'vue';
import { useCall } from '@/lib/query';
import { forDoctor, type Actor, type RxPatientsData, type RxTarget } from './model';

export type Link = (path: string, extra?: Record<string, string>) => string;

export function usePatients(actor: MaybeRefOrGetter<Actor>) {
  return useCall<RxPatientsData>('rx_patients', () => forDoctor(toValue(actor)));
}
export type PatientsQuery = ReturnType<typeof usePatients>;

export interface Row { key: string; t: RxTarget; icon: string; title: string; sub: string; tag: string | null }
export interface Group { key: string; title: string; rows: Row[]; empty?: string }
