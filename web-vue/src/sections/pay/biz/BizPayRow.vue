<script setup lang="ts">
// ব্যবসার তালিকার একটা পেমেন্ট: কে, কীসের, কত, কীভাবে (মাধ্যম, TrxID, পাঠানো নম্বরের শেষ ৪ অঙ্ক), কখন;
// "পেয়েছি" (নিশ্চিত ধাপ → রসিদ) / "পাইনি" (কারণ) — রোগী জানালে; "টাকা ফেরত" — নিশ্চিত হওয়ার পর (মালিক); রসিদ।
import { computed, ref } from 'vue';
import { Button, Tag, toast, useNow } from '@/ui/kit';
import { num } from '@/lib/i18n';
import { formatDayOf, formatTime } from '@/lib/format';
import { money, pickInvoice, type Invoice } from '../../invoices/model';
import * as api from '../api';
import { canConfirm, canReject, canRefund, type PayRequest, type RejectReason } from '../model';
import { T, methodName, purposeIcon, purposeText, rejectText, statusTextBiz, statusTone } from '../text';
import PayDecide, { type ConfirmChecks, type DecideTexts } from '../PayDecide.vue';
import RefundDialog from './RefundDialog.vue';
import Icon from '@/ui/Icon.vue';

const props = defineProps<{ r: PayRequest; owner: boolean; extra: Record<string, unknown> }>();
// রসিদ দেখানো বাইরে (তালিকা বদলালে এই সারি চলে গেলেও ডায়ালগ থাকে)
const emit = defineEmits<{ changed: []; receipt: [inv: Invoice]; receiptId: [id: string] }>();
const now = useNow(60_000);
const asking = ref<'confirm' | 'reject' | null>(null);
const refunding = ref(false);
const c = computed(() => props.r.claim);
const waitedH = computed(() => (c.value?.at && props.r.status === 'claimed' ? Math.floor((now.value - c.value.at) / 3600_000) : 0));
const when = (ms: number) => formatDayOf(ms) + ', ' + formatTime(ms);
const texts = computed<DecideTexts>(() => ({
  confirmTitle: T.confirmTitle(),
  confirmText: T.confirmText(money(props.r.amount), methodName(c.value?.method ?? ''), c.value?.trxId ?? '—', c.value?.senderLast4 ?? '—'),
  confirmLabel: T.received(),
  rejectTitle: T.rejectTitle(),
  rejectText: T.rejectText(),
  rejectLabel: T.notReceived(),
  reasonLabel: T.rejectNote(),
  reasonPh: T.rejectNotePh(),
  trx: c.value?.trxId,
}));
async function confirm(checks: ConfirmChecks | null) {
  const inv = pickInvoice(await api.confirmPay(props.r.id, checks, props.extra));
  toast(T.confirmed());
  if (inv) emit('receipt', inv);
}
async function reject(reason: RejectReason, note: string) {
  await api.rejectPay(props.r.id, reason, note, props.extra);
  toast(T.rejected());
}
function done() {
  asking.value = null;
  refunding.value = false;
  void api.invalidatePay();
  emit('changed');
}
</script>

<template>
  <li data-testid="biz-pay" :data-status="r.status" :data-id="r.id">
    <div class="row gap-8">
      <Icon :name="purposeIcon(r.purpose)" :size="20" />
      <span class="t-title-s grow ellipsis">{{ r.payerName || purposeText(r.purpose) }}</span>
      <span class="num semi" data-testid="biz-pay-amount">{{ money(r.amount) }}</span>
    </div>
    <div class="row wrap gap-6">
      <Tag :tone="statusTone(r.status)" data-testid="biz-pay-state">{{ statusTextBiz(r.status) }}</Tag>
      <span class="t-body-s muted grow">{{ [r.payerName ? purposeText(r.purpose) : '', when(r.claim?.at || r.createdAt)].filter(Boolean).join(' · ') }}</span>
      <span v-if="waitedH >= 24" class="t-body-s pay-late" data-testid="biz-pay-late">{{ T.late() }}</span>
    </div>
    <p v-if="c && c.trxId" class="t-body-m" data-testid="biz-pay-claim">
      {{ methodName(c.method) }} · TrxID <span class="pay-trx">{{ c.trxId }}</span>{{ c.senderLast4 ? ' · …' + num(c.senderLast4) : '' }}
    </p>
    <p v-if="c?.byName" class="t-body-s muted">{{ T.sentBy(c.byName) }}</p>
    <p v-if="r.status === 'rejected' && r.rejectReason" class="t-body-s muted">{{ rejectText(r.rejectReason) }}{{ r.rejectNote ? ' — ' + r.rejectNote : '' }}</p>
    <p v-if="r.refund" class="t-body-s muted">{{ T.refund() }}: {{ money(r.refund.amount) }} · TrxID <span class="pay-trx">{{ r.refund.trxId }}</span></p>
    <div class="row wrap gap-6 pay-row-actions">
      <Button v-if="canConfirm(r)" small :variant="r.status === 'rejected' ? 'outline' : 'filled'" icon="check_circle" data-testid="pay-confirm" @click="asking = 'confirm'">{{ r.status === 'rejected' ? T.reconfirm() : T.received() }}</Button>
      <Button v-if="canReject(r)" small variant="text" data-testid="pay-reject" @click="asking = 'reject'">{{ T.notReceived() }}</Button>
      <Button v-if="r.invoiceId" small variant="text" icon="list_alt" data-testid="biz-pay-receipt" @click="emit('receiptId', r.invoiceId)">{{ T.receipt() }}</Button>
      <Button v-if="owner && canRefund(r)" small variant="danger-text" data-testid="pay-refund-open" @click="refunding = true">{{ T.refund() }}</Button>
    </div>
    <PayDecide v-if="asking" :mode="asking" :texts="texts" :claim="c" :amount-text="money(r.amount)" :on-confirm="confirm" :on-reject="reject" @close="asking = null" @done="done" />
    <RefundDialog v-if="refunding" :r="r" :extra="extra" @close="refunding = false" @done="done" />
  </li>
</template>
