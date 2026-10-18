// কেয়ারগিভার মোড (অ্যাপের CareMode): অন্য কারো (রোগীর) হয়ে কাজ — শুধু অনুমোদিত কাজে রোগীর uid যায়।
import { useSyncExternalStore } from 'react';

const ACTIONS = new Set([
  'rx_mine', 'rx_get', 'rx_claim', 'rx_activity', 'rx_pharmacy_options', 'rx_send_pharmacy', 'rx_order_withdraw', 'rx_shop_find',
  'rx_send_lab', 'rx_lab_options', 'labs', 'lab_prices_public', 'lab_order_get', 'review_pay', 'labtests_get',
  'lab_home_change', 'lab_home_cancel',
  'my_appointments', 'book', 'appointment_update', 'providers', 'family_get',
  'appointment_queue', 'appointment_late',
]);

let state: { uid: string; name: string } | null = null;
const subs = new Set<() => void>();

const changeListeners = new Set<() => void>();
/** মোড বদলালে (query.ts পুরোনো ক্যাশ ফেলে দেয়) */
export function onCareChange(f: () => void) {
  changeListeners.add(f);
}
export function setCareMode(v: { uid: string; name: string } | null) {
  if ((state?.uid ?? null) === (v?.uid ?? null)) return;
  state = v;
  subs.forEach((f) => f());
  changeListeners.forEach((f) => f());
}
/** এখন কার হয়ে (না থাকলে null) */
export const careUid = () => state?.uid ?? null;
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
