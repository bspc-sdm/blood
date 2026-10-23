// "পেমেন্ট" পাতায় কোন কোন ব্যবসা: ফার্মেসি, প্যাথলজি (মালিকের uid), নিজের চেম্বার (ডাক্তারের uid), যেসব ডাক্তারের সহকারী
// (চেম্বার, শুধু মেলানো — সেটিং নয়), অ্যাম্বুলেন্সের প্রতিটা গাড়ি। শুধু চালু সুইচের ধরন (features.ts bizKindOn)।
import { computed } from 'vue';
import { useSession } from '@/lib/session';
import { tr } from '@/lib/i18n';
import type { BizKind } from '../model';
import { bizKindOn } from '../features';
import { kindText } from '../text';

export interface PayBiz {
  kind: BizKind;
  bizId: string;
  name: string;
  /** মালিক/ডাক্তার নিজে (সেটিং, ফেরত, CSV); সহকারী নয় */
  owner: boolean;
  /** সহকারী: প্রতিটা কাজে doctorUid */
  extra: Record<string, unknown>;
}
export const bizKey = (b: Pick<PayBiz, 'kind' | 'bizId'>) => `${b.kind}:${b.bizId}`;

export function useMyPayBiz() {
  const s = useSession();
  return computed<PayBiz[]>(() => {
    const out: PayBiz[] = [];
    const w = s.work;
    const ok = (x: { status?: string } | null | undefined) => !!x && (x.status === undefined || x.status === 'approved');
    if (ok(w.pharmacy) && bizKindOn('pharmacy')) out.push({ kind: 'pharmacy', bizId: String(w.pharmacy.id ?? s.uid ?? ''), name: String(w.pharmacy.name ?? kindText('pharmacy')), owner: true, extra: {} });
    if (ok(w.lab) && bizKindOn('lab')) out.push({ kind: 'lab', bizId: String(w.lab.id ?? s.uid ?? ''), name: String(w.lab.name ?? kindText('lab')), owner: true, extra: {} });
    if (w.professional === 'doctor' && s.uid && bizKindOn('chamber')) out.push({ kind: 'chamber', bizId: s.uid, name: tr('My chamber', 'আমার চেম্বার'), owner: true, extra: {} }); // bn-ok
    if (bizKindOn('chamber')) {
      for (const a of w.assisting) out.push({ kind: 'chamber', bizId: a.doctorUid, name: a.name, owner: false, extra: { doctorUid: a.doctorUid } });
    }
    if (bizKindOn('ambulance')) {
      for (const a of w.ambulances.filter(ok)) out.push({ kind: 'ambulance', bizId: String(a.id), name: [a.name, a.vehicleNo].filter(Boolean).join(' · '), owner: true, extra: {} });
    }
    return out.filter((b) => b.bizId);
  });
}
