<script setup lang="ts">
// ডাক্তার/সহকারী: অনলাইন অ্যাপয়েন্টমেন্টের ফি — অবস্থা, রোগীর দেওয়া TrxID, "ফি পেয়েছি" / "পাইনি" — দুটোতেই "নিশ্চিত?" ধাপ
// (LIFOLO PAY-এর একই PayDecide); আর সময় হলে "ভিডিও কলে যুক্ত হোন"।
// পেমেন্ট থাকলে (payRequest/payRequestId, pay_manual চালু) pay_confirm / pay_reject (কারণের কোড); নইলে আগের appt_pay_confirm / appt_pay_reject।
// দুই পথেই "ফি পেয়েছি"-তে তিনটা টিক (checks:{trxId, amount, last4}) — নইলে সার্ভার 400 checks_required।
// অবস্থা তালিকার payRequestId/payStatus থেকেই; পুরো অনুরোধ (TrxID, শেষ ৪) শুধু ডায়ালগ খুললে আনা হয়।
import { computed, ref } from 'vue';
import { Button, Tag, toast } from '@/ui/kit';
import { call } from '@/lib/api';
import { tr } from '@/lib/i18n';
import { useQ } from '@/lib/query';
import { T, payLabelPro, payTone, type PayState } from './index';
import JoinCall from './JoinCall.vue';
import { pickInvoice, type Invoice } from '../../invoices/model';
import { T as IT } from '../../invoices/text';
import { invFeature } from '../../invoices/features';
import ReceiptDialog from '../../invoices/ReceiptDialog.vue';
import InvoiceByIdDialog from '../../invoices/InvoiceByIdDialog.vue';
import PayDecide, { type ConfirmChecks, type DecideTexts } from '../../pay/PayDecide.vue';
import * as payApi from '../../pay/api';
import { onlineFeeOnPay } from '../../pay/features';
import { usePayLive } from '../../pay/live';
import type { PayLink, PayRequest, PayStatus, RejectReason } from '../../pay/model';
import { money } from '../../invoices/model';
import { methodName, rejectText, statusText } from '../../pay/text';
import { T as PT } from '../../pay/text';

const props = defineProps<{
  a: { id: string; online: boolean; status: string; date: number; start: string; startAt?: number; payState: PayState; payRef: string; noFee?: boolean; invoiceId?: string | null; payLink?: PayLink | null };
  /** রোগীর নাম (কলের পাতায়) */
  name?: string;
  /** সহকারী: { doctorUid } — প্রতিটা কাজে যায় */
  extra?: Record<string, unknown>;
}>();
const emit = defineEmits<{ changed: [] }>();
const asking = ref<'confirm' | 'reject' | null>(null);
// ফি পেয়েছি → সার্ভার রসিদ দেয় (inv_chamber চালু থাকলে): সাথে সাথে দেখা/ছাপা; পরে "রসিদ দেখুন"
const receipt = ref<Invoice | null>(null);
const receiptId = ref<string | null>(null);
const live = computed(() => props.a.status === 'confirmed' || props.a.status === 'completed');

// ---------- নতুন পথ: তালিকার payRequestId/payStatus; বিস্তারিত (দাবি) শুধু "ফি পেয়েছি"/"পাইনি" খুললে ----------
const link = computed(() => (onlineFeeOnPay() ? props.a.payLink ?? null : null));
usePayLive();
const rq = useQ<PayRequest>(() => ({
  queryKey: ['pay_request_get', link.value?.id ?? '', 'pro', props.extra?.doctorUid ?? null],
  queryFn: () => payApi.getRequest(link.value!.id),
  enabled: !!link.value && !link.value.request && !!asking.value,
  staleTime: 10_000,
}));
const req = computed<PayRequest | null>(() => rq.data ?? link.value?.request ?? null);
// তালিকার payStatus আগে ("পেয়েছি"/"পাইনি"-র পর তালিকা নতুন করে আসে); তালিকায় না থাকলে আনা অনুরোধের
const pst = computed<PayStatus | null>(() => link.value?.status ?? link.value?.request?.status ?? rq.data?.status ?? null);
const asState = (st: PayStatus): PayState => (st === 'confirmed' || st === 'refunded' ? 'paid' : st === 'claimed' ? 'claimed' : st === 'rejected' ? 'rejected' : 'unpaid');

/** তালিকায় দেখানোর অবস্থা আর TrxID — দুই পথেই একই রকম */
const state = computed<PayState>(() => (link.value && pst.value ? asState(pst.value) : props.a.payState));
const stateText = computed(() => (link.value && pst.value && ['refunded', 'expired', 'cancelled'].includes(pst.value) ? statusText(pst.value) : payLabelPro(state.value)));
const trxRef = computed(() => (link.value ? req.value?.claim?.trxId || props.a.payRef : props.a.payRef));
const invoiceId = computed(() => (link.value ? req.value?.invoiceId ?? props.a.invoiceId ?? null : props.a.invoiceId ?? null));
// নতুন পথে "ফি পেয়েছি" শুধু রোগী জানালে (বা ভুল করে "পাইনি" বলার পর) — তিন টিক মেলাতে TrxID/শেষ ৪ লাগে; নগদে পেলে চেম্বারের ডেস্ক
const canGot = computed(() => live.value && !props.a.noFee && (link.value ? pst.value === 'claimed' || pst.value === 'rejected' : props.a.payState !== 'paid'));
/** নতুন পথের তিন টিকের ধাপে দাবি লাগে — খোলার পর আসা পর্যন্ত অপেক্ষা */
const waitingClaim = computed(() => !!link.value && !!asking.value && !req.value?.claim && !rq.isError);
const canNot = computed(() => live.value && !props.a.noFee && (link.value ? pst.value === 'claimed' : props.a.payState === 'claimed'));

const texts = computed<DecideTexts>(() => {
  const c = req.value?.claim;
  return {
    confirmTitle: T.gotTitle(),
    confirmText: link.value && c ? PT.confirmText(money(req.value!.amount), methodName(c.method), c.trxId, c.senderLast4) : T.gotText() + (trxRef.value ? ' TrxID: ' + trxRef.value : ''),
    confirmLabel: T.got(),
    rejectTitle: T.notGotTitle(),
    rejectText: T.notGotText(),
    rejectLabel: T.notGot(),
    reasonLabel: T.reasonLabel(),
    reasonPh: T.reasonPh(),
    trx: trxRef.value,
  };
});
async function confirm(checks: ConfirmChecks) {
  if (link.value && req.value?.claim) receipt.value = pickInvoice(await payApi.confirmPay(link.value.id, checks, props.extra ?? {}));
  else receipt.value = pickInvoice(await call('appt_pay_confirm', { appointmentId: props.a.id, checks, ...(props.extra ?? {}) }));
}
async function reject(reason: RejectReason, note: string) {
  if (link.value) await payApi.rejectPay(link.value.id, reason, note, props.extra ?? {});
  else await call('appt_pay_reject', { appointmentId: props.a.id, ...(note ? { reason: note } : {}), ...(props.extra ?? {}) });
}
function done() {
  asking.value = null;
  toast(tr('Saved.', 'সেভ হয়েছে।'));
  if (link.value) void payApi.invalidatePay();
  emit('changed');
}
</script>

<template>
  <div class="row wrap gap-6 appt-pay-pro" data-testid="online-pay-pro" :data-pay="state">
    <Tag tone="info">{{ T.online() }}</Tag>
    <Tag v-if="!a.noFee" :tone="payTone(state)" data-testid="pay-state">{{ stateText }}</Tag>
    <span v-if="trxRef" class="t-body-s" data-testid="pay-ref">TrxID: <span class="appt-trx">{{ trxRef }}</span></span>
    <span v-if="link && req?.rejectReason && pst === 'rejected'" class="t-body-s muted">{{ rejectText(req.rejectReason) }}</span>
    <Button v-if="canGot" small variant="tonal" icon="check_circle" :busy="waitingClaim && asking === 'confirm'" data-testid="pay-got" @click.stop="asking = 'confirm'">{{ T.got() }}</Button>
    <Button v-if="canNot" small variant="text" data-testid="pay-not" @click.stop="asking = 'reject'">{{ T.notGot() }}</Button>
    <Button v-if="invFeature('inv_chamber') && state === 'paid' && invoiceId" small variant="text" icon="list_alt" data-testid="pay-receipt" @click.stop="receiptId = invoiceId">{{ IT.seeReceipt() }}</Button>
    <JoinCall :a="a" provider :name="name" small />
  </div>
  <ReceiptDialog v-if="receipt" :inv="receipt" @close="receipt = null" />
  <InvoiceByIdDialog v-if="receiptId" :id="receiptId" @close="receiptId = null" />
  <PayDecide
    v-if="asking && !waitingClaim"
    :mode="asking"
    :texts="texts"
    :claim="link ? req?.claim ?? null : null"
    :amount-text="req ? money(req.amount) : link?.amount != null ? money(link.amount) : ''"
    :free-reason="!link"
    :on-confirm="confirm"
    :on-reject="reject"
    @close="asking = null"
    @done="done"
  />
</template>
