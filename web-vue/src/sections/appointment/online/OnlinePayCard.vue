<script setup lang="ts">
// রোগী: অনলাইন অ্যাপয়েন্টমেন্টের ফি — এখন LIFOLO PAY-এর একই শিটে (sections/pay/PaySheet.vue)।
// অ্যাপয়েন্টমেন্টে পেমেন্ট থাকলে (payRequest/payRequestId, আর pay_manual চালু) নতুন পথ: চালু মাধ্যম, TrxID + শেষ ৪ অঙ্ক (pay_claim)।
// নইলে আগের পথ: ডাক্তারের bKash নম্বর (কপি), ধাপ, "টাকা পাঠিয়েছি" (+ ঐচ্ছিক TrxID → appt_pay_claim),
// অবস্থা: পাঠানো হয়নি / যাচাই হচ্ছে / ফি পরিশোধিত / ডাক্তার পাননি (আবার জানানো যায়)।
import { computed, ref } from 'vue';
import { Button, InfoText, Tag, toast, useNow } from '@/ui/kit';
import { ServerError, call, errorText } from '@/lib/api';
import { tr } from '@/lib/i18n';
import type { Appointment } from '../model';
import { T, TRX_LEN, canReportNoShow, payLabel, payTone, trxInput, trxOk, type PayState } from './index';
import ReasonReportDialog from '../ReasonReportDialog.vue';
import PaySheet from '../../pay/PaySheet.vue';
import PayRequestSheet from '../../pay/PayRequestSheet.vue';
import type { LegacyMode } from '../../pay/sheet';
import type { PayRequest, PayStatus } from '../../pay/model';
import { statusText } from '../../pay/text';
import { onlineFeeOnPay } from '../../pay/features';

const props = defineProps<{ a: Appointment }>();
const emit = defineEmits<{ changed: [] }>();
const reporting = ref(false);
const reported = ref(false);
const now = useNow(30_000);
// ফি দিয়েছেন কিন্তু ডাক্তার কলে আসেননি → appt_report
const canReport = computed(() => !reported.value && canReportNoShow(props.a, now.value));
async function report(reason: string) {
  await call('appt_report', { appointmentId: props.a.id, reason });
  reporting.value = false;
  reported.value = true;
  toast(tr('Reported. An admin will review it.', 'রিপোর্ট করা হয়েছে। অ্যাডমিন দেখবেন।'));
}

// ---------- নতুন পথ (pay request) ----------
const link = computed(() => (onlineFeeOnPay() ? props.a.payLink : null));
const live = ref<PayRequest | null>(null);
const payStatus = computed<PayStatus | null>(() => live.value?.status ?? link.value?.request?.status ?? link.value?.status ?? null);
const asState = (st: PayStatus): PayState => (st === 'confirmed' ? 'paid' : st === 'claimed' ? 'claimed' : st === 'rejected' ? 'rejected' : 'unpaid');
/** চেম্বারের নামেই অবস্থা ("ফি পরিশোধিত" …); ফেরত/মেয়াদ শেষ/বাতিল — পেমেন্টের নাম */
const chamberLabel = (st: PayStatus) => (st === 'refunded' || st === 'expired' || st === 'cancelled' ? statusText(st) : payLabel(asState(st)));
function onPay(r: PayRequest | null) {
  if (r) live.value = r;
  emit('changed');
}

// ---------- আগের পথ (appt_pay_claim) ----------
const st = computed(() => props.a.payState);
const toStatus = (p: PayState): PayStatus => (p === 'paid' ? 'confirmed' : p === 'claimed' ? 'claimed' : p === 'rejected' ? 'rejected' : 'open');
const legacyReq = computed<PayRequest>(() => ({
  id: '', kind: 'chamber', bizId: '', bizName: props.a.otherName, payerUid: '', payerName: '', refType: 'appointment', refId: props.a.id,
  purpose: 'chamber_fee', amount: 0,
  methods: props.a.bkashNumber ? [{ method: 'bkash', number: props.a.bkashNumber, type: 'personal', active: true }] : [],
  status: toStatus(st.value), claim: props.a.payRef ? { method: 'bkash', trxId: props.a.payRef, senderLast4: '', at: 0, byName: '' } : null,
  confirmedAt: null, confirmedBy: null, rejectReason: null, rejectNote: props.a.payReason || null, rejectedAt: null, refund: null,
  invoiceId: props.a.invoiceId ?? null, refundableUntil: null, expiresAt: null, createdAt: 0, demo: false,
}));
const legacy: LegacyMode = {
  steps: () => [T.step1(), T.step2(), T.step3(), T.step4()],
  sendLabel: T.sent,
  trxLabel: T.trx,
  trxHint: T.trxHint,
  trxPh: T.trxPh,
  trxMax: TRX_LEN,
  trxClean: trxInput,
  trxValid: trxOk,
  trxErr: T.errTrx,
  async claim(trx: string) {
    await call('appt_pay_claim', { appointmentId: props.a.id, ...(trx ? { payRef: trx } : {}) });
    toast(T.sentToast());
  },
  // R7: একই TrxID এই ডাক্তারের কাছে আগে (409 pay_ref_used), ঘণ্টায় ৫ বারের বেশি (429 pay_claim_rate)
  errText: (e: unknown) => {
    const code = e instanceof ServerError ? e.code : undefined;
    return code === 'pay_ref_used' ? T.trxUsed() : code === 'pay_claim_rate' ? T.claimRate() : errorText(e);
  },
  statusText: (s: PayStatus) => payLabel(asState(s)),
  copiedToast: T.copied,
  numberLabel: T.bkash,
  checkingText: T.checking,
  rejectedText: T.rejected,
  reasonPrefix: T.reason,
  howTo: T.howTo,
};
</script>

<template>
  <section class="card col gap-12 appt-pay" data-testid="online-pay" :data-pay="a.noFee ? 'nofee' : link ? payStatus ?? 'loading' : st">
    <div class="row">
      <h3 class="t-title-s semi grow">{{ T.payTitle() }}</h3>
      <template v-if="!a.noFee">
        <Tag v-if="link && payStatus" :tone="payTone(asState(payStatus))" data-testid="pay-state">{{ chamberLabel(payStatus) }}</Tag>
        <Tag v-else-if="!link" :tone="payTone(st)" data-testid="pay-state">{{ payLabel(st) }}</Tag>
      </template>
    </div>
    <p v-if="a.noFee" class="t-body-l">{{ tr('No fee', 'ফি নেই') }}</p>
    <template v-else>
      <p v-if="a.fee" class="t-title-m">{{ tr('Fee: ', 'ফি: ') + a.fee }}</p>
      <InfoText v-if="a.status === 'pending'" :text="T.afterConfirm()" />
      <PayRequestSheet v-if="link" :id="link.id" :initial="link.request" bare :locked="a.status !== 'confirmed'" @loaded="(r) => (live = r)" @changed="onPay" />
      <PaySheet v-else :req="legacyReq" :legacy="legacy" bare :locked="a.status !== 'confirmed'" @changed="emit('changed')" />
    </template>
    <p v-if="a.onlineNote" class="t-body-m appt-note pre">{{ tr('Note: ', 'নোট: ') + a.onlineNote }}</p>
    <div v-if="canReport" class="row wrap gap-6 appt-pay-report">
      <span class="t-body-s muted grow">{{ T.reportWhy() }}</span>
      <Button small variant="text" icon="flag" data-testid="appt-report" @click="reporting = true">{{ T.report() }}</Button>
    </div>
    <InfoText v-if="reported" :text="tr('Reported. An admin will review it.', 'রিপোর্ট করা হয়েছে। অ্যাডমিন দেখবেন।')" />
  </section>
  <ReasonReportDialog v-if="reporting" :on-submit="report" @close="reporting = false" />
</template>
