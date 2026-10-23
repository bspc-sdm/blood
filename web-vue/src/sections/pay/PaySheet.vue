<script setup lang="ts">
// একটাই পেমেন্টের শিট (PAYMENTS.md "Clients"): টাকা, কীসের, কাকে; চালু মাধ্যমগুলো থেকে বাছাই; নম্বর আর কপি;
// ধরন অনুযায়ী ধাপ (পার্সোনাল → Send Money, মার্চেন্ট → Payment); TrxID + শেষ ৪ অঙ্ক → "পাঠিয়েছি" (pay_claim);
// অবস্থার ধারা; ফেরত এলে "পেয়েছি"/"পাইনি" (pay_refund_ack / রিপোর্ট); "সমস্যা জানান" (pay_report)।
// নিরাপত্তা (docs/test-plan/PAYMENTS-SECURITY-REVIEW.md): সবসময় "শুধু এখানে দেখানো নম্বরে…" সতর্কতা, নম্বর লেখার কোনো ঘর নেই (শুধু দেখানো
// নম্বর), পাঠানোর আগে ফেরতের নিয়ম, ডেমোতে "আসল টাকা পাঠাবেন না", "জানিয়েছেন" কখনো পরিশোধিতের রঙে নয়, দোকান "পাইনি" বললে আলাদা
// বার্তা আর আপত্তি, ফেরত "পাঠিয়েছে বলছে" (refund_claimed) থেকে রোগীর "পেয়েছি"-তে তবেই শেষ।
// legacy দিলে আগের অনলাইন চেম্বারের পথ (appt_pay_claim; শুধু bKash, TrxID ঐচ্ছিক) — sheet.ts।
// bare: উপরের শিরোনাম/টাকা/অবস্থা বাইরের কার্ড দেখায়। locked: এখনো পাঠানো যায় না (যেমন ডাক্তার নিশ্চিত করেননি)।
import { computed, ref, watch } from 'vue';
import { Button, ChoiceChips, ErrorText, InfoText, Tag, TextInput, toast } from '@/ui/kit';
import { ServerError, errorText } from '@/lib/api';
import { num } from '@/lib/i18n';
import { formatDateTime } from '@/lib/format';
import { money } from '../invoices/model';
import InvoiceLink from '../invoices/InvoiceLink.vue';
import * as api from './api';
import { activeMethods, canClaim, isFinal, owesRefund, last4Clean, last4Valid, needsRefundAck, timelineOf, trxClean, trxMax, trxValid, type PayMethod, type PayRequest } from './model';
import { T, acctText, methodCode, methodName, methodOp, payErrText, purposeText, rejectText, statusShort, statusTone, stepText } from './text';
import type { LegacyMode } from './sheet';
import PayReportDialog from './PayReportDialog.vue';

const props = defineProps<{ req: PayRequest; legacy?: LegacyMode | null; bare?: boolean; locked?: boolean }>();
const emit = defineEmits<{ changed: [req: PayRequest | null] }>();

const methods = computed(() => activeMethods(props.req));
const pick = ref<string>('');
// আগে যেভাবে পাঠিয়েছিলেন (আবার জানালে), নইলে প্রথম চালু মাধ্যম
watch(
  methods,
  (ms) => {
    if (ms.some((m) => key(m) === pick.value)) return;
    const prev = props.req.claim ? ms.find((m) => m.method === props.req.claim!.method) : null;
    pick.value = ms.length ? key(prev ?? ms[0]) : '';
  },
  { immediate: true },
);
function key(m: PayMethod) {
  return `${m.method}:${m.number}`;
}
const cur = computed(() => methods.value.find((m) => key(m) === pick.value) ?? null);
const trx = ref('');
const last4 = ref('');
const busy = ref(false);
const err = ref<string | null>(null);
const errCode = ref<string | null>(null);
const copied = ref(false);
const reporting = ref<string | null | false>(false);
const reported = ref(false);
const acking = ref(false);
watch(pick, () => {
  copied.value = false;
  if (cur.value) trx.value = trxClean(trx.value, cur.value.method);
});

const st = computed(() => props.req.status);
const biz = computed(() => props.req.bizName || T.sheetTitle());
const open = computed(() => !props.locked && canClaim(props.req));
const claimable = computed(() => open.value && !!cur.value);
/** চালু কোনো নম্বর নেই (বা সব নতুন, ২৪ ঘণ্টা হয়নি) — নগদে দিন */
const noMethods = computed(() => open.value && !props.legacy && !methods.value.length);
/** নম্বর দেখানো: পাঠানো বা মেলানোর সময়টুকু */
const showNumber = computed(() => !props.locked && !!cur.value && (st.value === 'open' || st.value === 'rejected' || st.value === 'claimed'));
const amountText = computed(() => (props.req.amount > 0 ? money(props.req.amount) : ''));
/** মার্চেন্টে রেফারেন্স চাইলে — অনুরোধের ছোট চিহ্ন */
const refCode = computed(() => props.req.id.replace(/[^0-9A-Za-z]/g, '').slice(-6).toUpperCase());
const steps = computed<string[]>(() => {
  if (props.legacy) return props.legacy.steps();
  const m = cur.value;
  if (!m) return [];
  return [
    T.stepOpen(methodName(m.method), methodCode(m.method), methodOp(m.type)),
    T.stepSend(amountText.value || T.amount()),
    ...(m.type === 'merchant' ? [T.stepMerchantRef()] : []),
    T.stepTell(),
    T.stepWait(biz.value),
  ];
});
/** টাকা ফেরতের নিয়ম — পাঠানোর আগে (P11) */
const refundPolicy = computed<string[]>(() => {
  // সার্ভারের লেখা থাকলে সেটাই (pay_request_get policy)
  if (props.req.policy.length) return props.req.policy;
  const p = props.req.purpose;
  if (p === 'booking_advance') return props.req.refundableUntil ? [T.doctorCancelFull(), T.refundUntil(formatDateTime(props.req.refundableUntil))] : [T.refundAdvance(0)];
  if (p === 'ambulance_fare') return [T.refundFare()];
  return [T.refundGeneral(biz.value)];
});
const timeline = computed(() => (props.legacy ? [] : timelineOf(props.req)));
const stateText = computed(() => (props.legacy ? props.legacy.statusText(st.value) : statusShort(st.value, props.req.purpose, props.req.refundDue)));
/** কেয়ারগিভার নিজের ওয়ালেট থেকে দিলে (claim.by রোগী নন) */
const byCare = computed(() => !!props.req.claim?.by && !!props.req.payerUid && props.req.claim.by !== props.req.payerUid);
const canReport = computed(() => !props.legacy && !reported.value && !(isFinal(st.value) && st.value !== 'confirmed' && st.value !== 'refunded'));
const refundAmount = computed(() => money(props.req.refund?.amount || props.req.amount));

function copy() {
  const n = cur.value?.number;
  if (!n) return;
  navigator.clipboard?.writeText(n).then(
    () => {
      copied.value = true;
      toast(props.legacy ? props.legacy.copiedToast() : T.numberCopied());
    },
    () => (copied.value = false),
  );
}
function onTrx(v: string) {
  trx.value = props.legacy ? props.legacy.trxClean(v) : trxClean(v, cur.value?.method ?? 'bkash');
}
async function send() {
  err.value = null;
  errCode.value = null;
  if (props.legacy) {
    if (!props.legacy.trxValid(trx.value)) return void (err.value = props.legacy.trxErr());
    busy.value = true;
    try {
      await props.legacy.claim(trx.value);
      trx.value = '';
      emit('changed', null);
    } catch (e) {
      err.value = props.legacy.errText(e);
    } finally {
      busy.value = false;
    }
    return;
  }
  const m = cur.value;
  if (!m) return void (err.value = T.errPickMethod());
  if (!trxValid(trx.value, m.method)) return void (err.value = T.errTrx(m.method));
  if (!last4Valid(last4.value)) return void (err.value = T.errLast4());
  busy.value = true;
  try {
    const r = await api.claim(props.req.id, m.method, trx.value, last4.value);
    toast(T.sentToast(biz.value));
    trx.value = '';
    last4.value = '';
    emit('changed', r);
  } catch (e) {
    errCode.value = e instanceof ServerError ? e.code ?? null : null;
    err.value = payErrText(errCode.value ?? undefined, m.method, errorText(e));
  } finally {
    busy.value = false;
  }
}
async function ack() {
  acking.value = true;
  err.value = null;
  try {
    emit('changed', await api.refundAck(props.req.id));
  } catch (e) {
    err.value = errorText(e);
  } finally {
    acking.value = false;
  }
}
function onReported() {
  reporting.value = false;
  reported.value = true;
  toast(T.reportDone());
}
</script>

<template>
  <div class="col gap-12 pay-sheet" data-testid="pay-sheet" :data-status="st">
    <p v-if="req.demo || req.demoNote" class="pay-demo" role="alert" data-testid="pay-demo">{{ T.demoBanner() }}<template v-if="req.demoNote"><br /><span class="t-body-s">{{ req.demoNote }}</span></template></p>
    <div v-if="!bare" class="col gap-4 pay-head">
      <div class="row gap-8">
        <span class="t-label-l grow">{{ purposeText(req.purpose) }}</span>
        <Tag :tone="req.refundDue ? 'error' : statusTone(st)" data-testid="pay-state">{{ stateText }}</Tag>
      </div>
      <span v-if="amountText" class="pay-amount num" data-testid="pay-amount">{{ amountText }}</span>
      <span v-if="req.bizName" class="t-body-m muted" data-testid="pay-biz">{{ T.payTo(req.bizName) }}</span>
    </div>

    <!-- সবসময়: শুধু এই নম্বর; Lifolo PIN/OTP/টাকা চায় না (P2) -->
    <div class="pay-warn" role="note" data-testid="pay-warn">
      <p class="t-body-m semi">{{ T.onlyHere() }}</p>
      <p v-if="!legacy" class="t-body-s">{{ T.noPin() }}</p>
    </div>

    <p v-if="noMethods" class="t-body-m" data-testid="pay-no-methods">{{ T.noMethods() }}</p>

    <!-- নম্বর: বাছাই করা মাধ্যম, কপি; মার্চেন্ট হলে রেফারেন্স। লেখার ঘর নেই — শুধু দেখানো নম্বর -->
    <template v-if="showNumber && cur">
      <div v-if="methods.length > 1 && !legacy && st !== 'claimed'" class="col gap-6" data-testid="pay-methods">
        <span class="t-label-l">{{ T.pickMethod() }}</span>
        <ChoiceChips v-model="pick" :options="methods.map((m) => ({ value: key(m), label: methodName(m.method) }))" />
      </div>
      <div class="pay-number">
        <span class="col gap-2 grow">
          <span class="t-label-m muted">
            {{ legacy ? legacy.numberLabel() : T.numberOf(methodName(cur.method)) }}<template v-if="!legacy"> · <span data-testid="pay-acct-type">{{ acctText(cur.type) }}</span></template>
          </span>
          <span class="t-title-l num" data-testid="pay-number">{{ num(cur.number) }}</span>
          <span v-if="!legacy && cur.type === 'merchant'" class="t-body-s">{{ T.reference() }}: <span class="pay-trx" data-testid="pay-ref-code">{{ refCode }}</span></span>
        </span>
        <button type="button" class="btn tonal small" data-testid="pay-copy" @click="copy">{{ copied ? T.copied() : T.copy() }}</button>
      </div>
      <p v-if="st !== 'claimed'" class="t-body-s muted" data-testid="pay-check-number">{{ T.checkNumber() }}</p>
    </template>

    <!-- রোগী জানিয়েছেন: এখনো পরিশোধিত নয় -->
    <template v-if="st === 'claimed'">
      <InfoText :text="legacy ? legacy.checkingText() : T.checking(biz)" />
      <p v-if="req.claim?.trxId" class="t-body-m" data-testid="pay-claim-line">
        TrxID: <span class="pay-trx">{{ req.claim.trxId }}</span><template v-if="!legacy"> · {{ methodName(req.claim.method) }}{{ req.claim.senderLast4 ? ' · …' + num(req.claim.senderLast4) : '' }}</template>
      </p>
      <p v-if="req.claim?.byName || byCare" class="t-body-s muted">{{ req.claim?.byName ? T.sentBy(req.claim.byName) : T.sentByCare() }}</p>
    </template>
    <template v-if="st === 'confirmed'">
      <p v-if="req.claim?.trxId" class="t-body-m muted">TrxID: <span class="pay-trx">{{ req.claim.trxId }}</span></p>
      <div v-if="req.invoiceId" data-testid="pay-receipt"><InvoiceLink :ref-type="(req.refType as any)" :ref-id="req.refId" :invoice-id="req.invoiceId" /></div>
    </template>

    <!-- দোকান বলছে টাকা আসেনি: আলাদা বার্তা, যা জানিয়েছিলেন সব থাকে, আপত্তি (P3) -->
    <div v-if="st === 'rejected'" class="col gap-6 pay-rej" role="status" data-testid="pay-rejected">
      <template v-if="legacy">
        <p class="t-body-m">{{ legacy.rejectedText() }}</p>
        <p v-if="req.rejectNote || req.rejectReason" class="t-body-s">{{ legacy.reasonPrefix() + (req.rejectNote || req.rejectReason) }}</p>
      </template>
      <template v-else>
        <p class="t-body-m semi">{{ T.rejectedTitle() }}</p>
        <p v-if="req.rejectReason || req.rejectNote" class="t-body-s" data-testid="pay-reject-reason">
          {{ T.reasonIs([req.rejectReason ? rejectText(req.rejectReason) : '', req.rejectNote].filter(Boolean).join(' — ')) }}
        </p>
        <p v-if="req.rejectedAt" class="t-body-s">{{ T.rejectedWhen(formatDateTime(req.rejectedAt)) }}</p>
        <p v-if="req.claim" class="t-body-s" data-testid="pay-rejected-claim">
          {{ T.rejectedWhat() }}: {{ methodName(req.claim.method) }} · TrxID <span class="pay-trx">{{ req.claim.trxId }}</span>{{ amountText ? ' · ' + amountText : '' }}{{ req.claim.senderLast4 ? ' · …' + num(req.claim.senderLast4) : '' }}{{ req.claim.at ? ' · ' + formatDateTime(req.claim.at) : '' }}
        </p>
        <p class="t-body-s">{{ T.rejectedIfWrong() }}</p>
        <div v-if="!reported" class="row"><Button small variant="outline" icon="flag" data-testid="pay-dispute" @click="reporting = 'not_confirmed'">{{ T.dispute() }}</Button></div>
      </template>
    </div>

    <!-- পাঠানো যায়: খোলা, বা দোকান পায়নি বলেছে (TrxID ভুল হলে আবার) -->
    <template v-if="claimable && cur">
      <p v-if="!legacy && req.expiresAt" class="t-body-s muted" data-testid="pay-expires">{{ T.expiresAt(formatDateTime(req.expiresAt)) }}</p>
      <div v-if="!legacy" class="col gap-2 pay-policy" data-testid="pay-refund-policy">
        <span class="t-label-m">{{ T.refundPolicyTitle() }}</span>
        <span v-for="(x, i) in refundPolicy" :key="i" class="t-body-s">{{ x }}</span>
      </div>
      <div class="col gap-4">
        <span class="t-label-l">{{ legacy ? legacy.howTo() : T.howTo() }}</span>
        <ol class="pay-steps t-body-m" data-testid="pay-steps">
          <li v-for="(x, i) in steps" :key="i">{{ x }}</li>
        </ol>
      </div>
      <div :class="legacy ? 'col' : 'pay-inputs'">
        <TextInput
          :label="legacy ? legacy.trxLabel() : T.trx()"
          :hint="legacy ? legacy.trxHint() : T.trxHint(cur.method)"
          :maxlength="legacy ? legacy.trxMax : trxMax(cur.method)"
          :placeholder="legacy ? legacy.trxPh() : T.trxPh(cur.method)"
          autocomplete="off"
          autocapitalize="characters"
          spellcheck="false"
          data-testid="trx-input"
          :model-value="trx"
          @update:model-value="onTrx"
        />
        <TextInput
          v-if="!legacy"
          :label="T.last4()"
          :placeholder="T.last4Ph()"
          inputmode="numeric"
          maxlength="4"
          autocomplete="off"
          data-testid="last4-input"
          :model-value="last4"
          @update:model-value="(v: string) => (last4 = last4Clean(v))"
        />
      </div>
      <ErrorText :text="err" />
      <div v-if="errCode === 'pay_ref_used' || errCode === 'pay_ref_rejected'" class="row">
        <Button small variant="outline" icon="flag" data-testid="pay-ref-report" @click="reporting = 'not_confirmed'">{{ T.report() }}</Button>
      </div>
      <div class="row">
        <Button icon="check_circle" :busy="busy" data-testid="pay-claim" @click="send">{{ legacy ? legacy.sendLabel() : T.sent() }}</Button>
      </div>
      <p v-if="!legacy" class="t-body-s muted">{{ T.directNote(biz) }}</p>
    </template>

    <p v-if="st === 'expired'" class="t-body-m muted" data-testid="pay-expired">{{ T.expiredHead() }}</p>
    <p v-if="st === 'cancelled'" class="t-body-m muted">{{ T.cancelledHead() }}</p>

    <!-- ফেরত পাওনা / পাঠিয়েছে বলছে / ফেরত হয়েছে -->
    <div v-if="owesRefund(req)" class="col gap-4 pay-rej" data-testid="pay-refund-due">
      <p class="t-body-m semi">{{ T.refundDueHead(biz) }}</p>
      <p class="t-body-s">{{ T.refundDueText() }}</p>
    </div>
    <div v-if="(st === 'refund_claimed' || st === 'refunded') && req.refund" class="col gap-6 pay-refund" data-testid="pay-refund">
      <p class="t-body-m semi">{{ st === 'refund_claimed' ? T.refundClaimedHead(refundAmount) : T.refundHead(refundAmount) }}</p>
      <p v-if="req.refund.trxId" class="t-body-s">{{ T.refundTrx(req.refund.trxId) }}</p>
      <template v-if="needsRefundAck(req)">
        <p class="t-body-s">{{ T.refundCheck() }}</p>
        <div class="row wrap gap-8">
          <Button small icon="check_circle" :busy="acking" data-testid="refund-ack" @click="ack">{{ T.refundAck() }}</Button>
          <Button small variant="outline" data-testid="refund-not-got" @click="reporting = 'no_refund'">{{ T.refundNotGotBtn() }}</Button>
        </div>
      </template>
      <p v-else class="t-body-s muted" data-testid="refund-acked">{{ T.refundAcked() }}</p>
    </div>
    <ErrorText v-if="!claimable" :text="err" />

    <!-- অবস্থার ধারা -->
    <ol v-if="timeline.length > 1" class="pay-timeline" :aria-label="T.timeline()" data-testid="pay-timeline">
      <li v-for="(s, i) in timeline" :key="i" :class="[s.done ? 'done' : 'next', s.k]">
        <span class="pay-dot" aria-hidden="true" />
        <span class="t-body-s">{{ stepText(s.k, biz) }}</span>
        <span v-if="s.at" class="t-body-s muted">{{ formatDateTime(s.at) }}</span>
      </li>
    </ol>

    <div v-if="canReport" class="row">
      <Button small variant="text" icon="flag" data-testid="pay-report" @click="reporting = null">{{ T.report() }}</Button>
    </div>
    <InfoText v-if="reported" :text="T.reportDone()" />
    <PayReportDialog v-if="reporting !== false" :req="req" :reason="reporting" @close="reporting = false" @done="onReported" />
  </div>
</template>
