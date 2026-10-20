import { tr } from '@/lib/i18n';
import { BLOOD_GROUPS } from '@/lib/rules';

export interface Group { group: string; need: number; coming: number; full: boolean }
export interface Appeal {
  id: string; hospitalName: string; district: string; contactPhone: string; note: string; groups: Group[];
  endsAt: number; myGroup: string; myStatus: string; canCome: boolean; eligible: boolean;
}
export interface Ask { id: string; hospitalName: string; endedAt: number }

export function parseAppeal(o: any): Appeal {
  const g = o.groups ?? {};
  return {
    id: String(o.id ?? ''),
    hospitalName: o.hospitalName ?? '',
    district: o.district ?? '',
    contactPhone: o.contactPhone ?? '',
    note: o.note ?? '',
    groups: BLOOD_GROUPS.filter((k) => g[k]).map((k) => ({ group: k, need: g[k].need ?? 0, coming: g[k].coming ?? 0, full: !!g[k].full })),
    endsAt: typeof o.endsAt === 'number' ? o.endsAt : 0,
    myGroup: o.myGroup ?? '',
    myStatus: o.myStatus ?? '',
    canCome: !!o.canCome,
    eligible: !!o.eligible,
  };
}

export const THANKS_DONATED = () => tr('Thank you! Your last donation date is set to today.', 'ধন্যবাদ! আপনার শেষ রক্তদানের তারিখ আজ বসানো হয়েছে।');
