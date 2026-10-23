// "রোগীরা আপনাকে যেভাবে দেখেন": নিজের ব্যবসা রোগীদের তালিকায় যেভাবে আসে। নিজের তথ্যের কাজগুলো (pharmacy_me, lab_me,
// bloodbank_mine, ambulance_mine — সেশনের s.work) তালিকার একই ঘর দেয় (openNow-এ হাতে খোলা/বন্ধ, মজুদ, রেটিং …;
// backend/docs/LISTINGS.md "Provider side"), তাই একই ছাঁচ (listingOf) দিয়ে একই কার্ড। সুইচ বদলালে `patch` সাথে সাথে কার্ডে
// বসে, তারপর সেশনের তথ্য আবার আসে (useAfterChange) — নতুন তথ্য এলে patch বাদ।
import { reactive } from 'vue';
import type { Listing } from '@/ui/listing/model';
import { listingOf } from '@/sections/services/listing';
import type { Kind, Lists } from '@/sections/services/data';
import { useAfterChange } from './shared';

export interface OwnItem { id: string; district: string; upazilaId: number | null; status?: string }

export function useOwnListings(kind: Kind, lists: () => Lists | null = () => null) {
  const after = useAfterChange();
  const patches = reactive(new Map<string, { base: unknown; v: Record<string, unknown> }>());
  const raw = (it: OwnItem): any => {
    const p = patches.get(it.id);
    return p && p.base === it ? { ...it, ...p.v } : it;
  };
  const listing = (it: OwnItem): Listing => listingOf(kind, raw(it), lists());
  return {
    raw,
    listing,
    /** সুইচের পর: কার্ডে সাথে সাথে, তারপর সেশনের তথ্য আবার */
    patch(it: OwnItem, v: Record<string, unknown>) {
      const old = patches.get(it.id);
      patches.set(it.id, { base: it, v: { ...(old && old.base === it ? old.v : {}), ...v } });
      after();
    },
  };
}
