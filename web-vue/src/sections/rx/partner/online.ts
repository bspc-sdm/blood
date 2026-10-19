// অর্ডারের অবস্থা বদল (rx_order_update) — রোগী নোটিফিকেশন পান
import { ref } from 'vue';
import { toast } from '@/ui/kit';
import { call, errorText } from '@/lib/api';
import { tr } from '@/lib/i18n';
import { useInvalidate } from './common';
import type { PharmOrder } from './model';

export function useOrderUpdate() {
  const invalidate = useInvalidate();
  const busyId = ref<string | null>(null);
  const err = ref<string | null>(null);
  async function update(o: PharmOrder, status: 'ready' | 'accepted' | 'out' | 'cancelled', extra: Record<string, unknown> = {}) {
    busyId.value = o.id;
    err.value = null;
    try {
      await call('rx_order_update', { id: o.id, status, ...extra });
      toast(tr('The patient was told', 'রোগীকে জানানো হয়েছে'));
      await invalidate('rx_orders_pharmacy', 'rx_pharmacy_home');
      return true;
    } catch (e) {
      err.value = errorText(e);
      return false;
    } finally {
      busyId.value = null;
    }
  }
  return { busyId, err, update };
}
