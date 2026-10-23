// কেয়ারগিভার মোড (অ্যাপের CareMode): অন্য কারো (রোগীর) হয়ে কাজ — শুধু অনুমোদিত কাজে রোগীর uid যায়।
import { shallowRef, type Ref } from 'vue';

const ACTIONS = new Set([
  'rx_mine', 'rx_get', 'rx_claim', 'rx_activity', 'rx_mark_done', 'rx_pharmacy_options', 'rx_send_pharmacy', 'rx_order_withdraw', 'rx_shop_find',
  'rx_send_lab', 'rx_lab_options', 'labs', 'lab_prices_public', 'lab_order_get', 'review_pay', 'labtests_get',
  'lab_home_change', 'lab_home_cancel',
  'my_appointments', 'book', 'appointment_update', 'providers', 'family_get',
  'appointment_queue', 'appointment_late',
  'my_invoices', 'my_expenses',
  // পেমেন্ট (PAYMENTS.md: রোগী বা কেয়ারগিভার asUid দিয়ে)
  'my_payments', 'pay_request_get', 'pay_claim', 'pay_refund_ack', 'pay_report', 'pay_due_start',
]);

const state: Ref<{ uid: string; name: string } | null> = shallowRef(null);

const changeListeners = new Set<() => void>();
/** মোড বদলালে (query.ts পুরোনো ক্যাশ ফেলে দেয়) */
export function onCareChange(f: () => void) {
  changeListeners.add(f);
}
export function setCareMode(v: { uid: string; name: string } | null) {
  if ((state.value?.uid ?? null) === (v?.uid ?? null)) return;
  state.value = v;
  changeListeners.forEach((f) => f());
}
/** এখন কার হয়ে (না থাকলে null) */
export const careUid = () => state.value?.uid ?? null;
/** কেয়ারগিভার মোড (ref): null = নিজের হয়ে */
export function useCareMode(): Readonly<Ref<{ uid: string; name: string } | null>> {
  return state;
}
/** api.ts ডাকে: এই কাজে রোগীর uid লাগবে কি না */
export function careTarget(action: string): string | null {
  return state.value && ACTIONS.has(action) ? state.value.uid : null;
}
export const isCareAction = (a: string) => ACTIONS.has(a);
