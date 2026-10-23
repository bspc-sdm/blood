<script setup lang="ts">
// ফার্মেসির একটা অর্ডার (ফোনে তালিকার কার্ড, ডেস্কটপে ডান ভাগের বিস্তারিত) — একটাই মূল বোতাম (পরের ধাপের নাম), বাকিগুলো "আরও"-তে
// (আমার কাজ CONTRACT §২)। দোকানে এসেছেন: খুলুন → (আরও: তৈরি, পারব না); অনলাইন: অর্ডার নিলাম → পাঠিয়ে দিয়েছি → পৌঁছে দিয়েছি
// (আরও: খুলে ওষুধ লিখুন, পারব না)। "নতুন" ভাগে আবার "নতুন" চিহ্ন নয়।
import { computed } from 'vue';
import { Button, Icon, RowMenu, Tag, cx, type MenuItem } from '@/ui/kit';
import { num, tr } from '@/lib/i18n';
import { formatDateTime, timeAgo } from '@/lib/format';
import { isOpenOrder, orderStatusShop, orderTone, type PharmOrder } from './model';
import ModeTag from './ModeTag.vue';
import ContactActions from '@/calls/ContactActions.vue';
import { chatOn, openChatAt } from '@/chat/open';
import { T } from '../../invoices/text';
import { invFeature } from '../../invoices/features';
import AskPay from '../../pay/AskPay.vue';
import { payOn } from '../../pay/features';

export type OrderAct = 'open' | 'ready' | 'accept' | 'out' | 'deliver' | 'cancel' | 'receipt';
const props = defineProps<{ o: PharmOrder; on?: boolean; busy: boolean; disabled: boolean; pharmId: string }>();
const emit = defineEmits<{ act: [k: OrderAct] }>();
const online = computed(() => props.o.mode === 'online');
const inv = computed(() => invFeature('inv_pharmacy'));
interface A { k: OrderAct; label: string; icon?: string; danger?: boolean; testid?: string }
const openA = (): A => ({ k: 'open', label: online.value ? tr('Open & note medicines', 'খুলে ওষুধ লিখুন') : tr('Open', 'খুলুন'), icon: 'edit_note' });
const cancelA = (): A => ({ k: 'cancel', label: tr('Can\'t', 'পারব না'), icon: 'close', danger: true, testid: 'order-cant' });
/** [মূল, ...আরও] */
const acts = computed<A[]>(() => {
  const o = props.o;
  if (!isOpenOrder(o)) return inv.value && o.invoiceId ? [{ k: 'receipt', label: T.seeReceipt(), icon: 'list_alt' }] : [];
  if (!online.value) return [openA(), ...(o.status === 'sent' ? [{ k: 'ready' as const, label: tr('Ready', 'তৈরি'), icon: 'check_circle' }] : []), cancelA()];
  const deliver: A | null = inv.value ? { k: 'deliver', label: T.delivered(), icon: 'check_circle', testid: 'order-delivered' } : null;
  const out: A = { k: 'out', label: tr('Sent out', 'পাঠিয়ে দিয়েছি'), icon: 'local_shipping' };
  if (o.status === 'sent') return [{ k: 'accept', label: tr('Accept', 'অর্ডার নিলাম'), icon: 'check_circle' }, openA(), out, cancelA()];
  if (o.status === 'accepted') return [out, openA(), ...(deliver ? [deliver] : []), cancelA()];
  return deliver ? [deliver, openA(), cancelA()] : [openA(), cancelA()];
});
const more = computed<MenuItem[]>(() => acts.value.slice(1).map((a) => ({ label: a.label, icon: a.icon, danger: a.danger, testid: a.testid, disabled: props.disabled, onClick: () => emit('act', a.k) })));
</script>

<template>
  <article :class="cx('card col gap-6 rxp-ocard', on && 'on')" data-testid="order-card">
    <div class="row gap-8">
      <span class="t-title-s grow">{{ o.patientName || '—' }}</span>
      <ModeTag :remote="online" :icon="online ? 'local_shipping' : 'storefront'">{{ online ? tr('Online order', 'অনলাইন অর্ডার') : tr('In the shop', 'দোকানে এসেছেন') }}</ModeTag>
    </div>
    <div class="row wrap gap-6">
      <Tag v-if="o.status !== 'sent'" :tone="orderTone(o.status)">{{ orderStatusShop(o.status) }}</Tag>
      <span class="t-body-s muted" :title="formatDateTime(o.createdAt)">{{ timeAgo(o.createdAt) }}</span>
    </div>
    <span v-if="online && (o.price || o.eta)" class="icon-line"><Icon name="payments" /><span>{{ [o.price, o.eta].filter(Boolean).join(' · ') }}</span></span>
    <span v-if="online && o.address" class="icon-line"><Icon name="location_on" /><span>{{ o.address }}</span></span>
    <span v-if="online && o.phone" class="icon-line"><Icon name="call" /><a class="rxp-phone num" :href="`tel:${o.phone}`">{{ o.phone }}</a></span>
    <ContactActions :message="chatOn()" @message="openChatAt" ctx="rx_order" :ref-id="o.id" :peer-name="o.patientName" :phone="o.phone || null" :show="online && isOpenOrder(o)" />
    <span v-if="online && o.deliveryFee && isOpenOrder(o)" class="icon-line"><Icon name="local_shipping" /><span>{{ tr('Delivery charge: ', 'ডেলিভারি চার্জ: ') }}{{ num(o.deliveryFee) }}</span></span>
    <span v-if="o.note" class="icon-line"><Icon name="edit_note" /><span>{{ o.note }}</span></span>
    <span v-if="o.missed.length" class="t-body-s c-error">{{ tr('Not available: ', 'নেই: ') }}{{ o.missed.join(', ') }}</span>
    <div v-if="acts.length" class="q-acts rxp-row-actions">
      <Button
        :variant="acts[0].k === 'receipt' ? 'text' : 'filled'"
        :icon="acts[0].icon"
        :busy="busy"
        :disabled="disabled"
        :data-testid="acts[0].testid ?? 'order-primary'"
        @click="emit('act', acts[0].k)"
      >{{ acts[0].label }}</Button>
      <span class="grow" />
      <RowMenu :items="more" />
    </div>
    <AskPay v-if="online && (o.status === 'accepted' || o.status === 'out') && payOn('pay_delivery') && pharmId" kind="pharmacy" :biz-id="pharmId" ref-type="rx_order" :ref-id="o.id" purpose="delivery" />
  </article>
</template>
