// কেয়ারগিভার মোড (অ্যাপের CareMode): অন্য কারো (রোগীর) হয়ে কাজ — শুধু অনুমোদিত কাজে রোগীর uid যায়।
import { useSyncExternalStore } from 'react';

const ACTIONS = new Set([
  'rx_mine', 'rx_get', 'rx_claim', 'rx_activity', 'rx_pharmacy_options', 'rx_send_pharmacy', 'rx_order_withdraw', 'rx_shop_find',
  'rx_send_lab', 'labs', 'lab_order_get', 'review_pay', 'labtests_get',
  'my_appointments', 'book', 'appointment_update', 'providers', 'family_get',
]);

let state: { uid: string; name: string } | null = null;
const subs = new Set<() => void>();

export function setCareMode(v: { uid: string; name: string } | null) {
  state = v;
  subs.forEach((f) => f());
}
export function useCareMode() {
  return useSyncExternalStore(
    (f) => {
      subs.add(f);
      return () => subs.delete(f);
    },
    () => state,
  );
}
/** api.ts ডাকে: এই কাজে রোগীর uid লাগবে কি না */
export function careTarget(action: string): string | null {
  return state && ACTIONS.has(action) ? state.uid : null;
}
export const isCareAction = (a: string) => ACTIONS.has(a);
