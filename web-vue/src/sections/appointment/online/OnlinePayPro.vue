<script setup lang="ts">
// ডাক্তার/সহকারী: অনলাইন অ্যাপয়েন্টমেন্টের ফি — অবস্থা, রোগীর দেওয়া TrxID, "ফি পেয়েছি" (appt_pay_confirm) /
// "পাইনি" (appt_pay_reject, ঐচ্ছিক কারণ) — দুটোতেই "নিশ্চিত?" ধাপ; আর সময় হলে "ভিডিও কলে যুক্ত হোন"।
import { computed, ref } from 'vue';
import { Button, ConfirmDialog, Dialog, ErrorText, Tag, TextInput, toast } from '@/ui/kit';
import { call, errorText } from '@/lib/api';
import { tr } from '@/lib/i18n';
import { T, payLabelPro, payTone, type PayState } from './index';
import JoinCall from './JoinCall.vue';
import { pickInvoice, type Invoice } from '../../invoices/model';
import { T as IT } from '../../invoices/text';
import { invFeature } from '../../invoices/features';
import ReceiptDialog from '../../invoices/ReceiptDialog.vue';
import InvoiceByIdDialog from '../../invoices/InvoiceByIdDialog.vue';

const props = defineProps<{
  a: { id: string; online: boolean; status: string; date: number; start: string; startAt?: number; payState: PayState; payRef: string; noFee?: boolean; invoiceId?: string | null };
  /** রোগীর নাম (কলের পাতায়) */
  name?: string;
  /** সহকারী: { doctorUid } — প্রতিটা কাজে যায় */
  extra?: Record<string, unknown>;
}>();
const emit = defineEmits<{ changed: [] }>();
const asking = ref<'got' | 'not' | null>(null);
const reason = ref('');
const busy = ref(false);
const err = ref<string | null>(null);
// ফি পেয়েছি → সার্ভার রসিদ দেয় (inv_chamber চালু থাকলে): সাথে সাথে দেখা/ছাপা; পরে "রসিদ দেখুন"
const receipt = ref<Invoice | null>(null);
const receiptId = ref<string | null>(null);
const live = computed(() => props.a.status === 'confirmed' || props.a.status === 'completed');
const canGot = computed(() => live.value && !props.a.noFee && props.a.payState !== 'paid');
const canNot = computed(() => live.value && !props.a.noFee && props.a.payState === 'claimed');

function ask(k: 'got' | 'not') {
  err.value = null;
  reason.value = '';
  asking.value = k;
}
async function act() {
  const k = asking.value;
  if (!k) return;
  busy.value = true;
  err.value = null;
  try {
    if (k === 'got') receipt.value = pickInvoice(await call('appt_pay_confirm', { appointmentId: props.a.id, ...(props.extra ?? {}) }));
    else await call('appt_pay_reject', { appointmentId: props.a.id, ...(reason.value.trim() ? { reason: reason.value.trim() } : {}), ...(props.extra ?? {}) });
    asking.value = null;
    toast(tr('Saved.', 'সেভ হয়েছে।'));
    emit('changed');
  } catch (e) {
    err.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <div class="row wrap gap-6 appt-pay-pro" data-testid="online-pay-pro" :data-pay="a.payState">
    <Tag tone="info">{{ T.online() }}</Tag>
    <Tag v-if="!a.noFee" :tone="payTone(a.payState)" data-testid="pay-state">{{ payLabelPro(a.payState) }}</Tag>
    <span v-if="a.payRef" class="t-body-s" data-testid="pay-ref">TrxID: <span class="appt-trx">{{ a.payRef }}</span></span>
    <Button v-if="canGot" small variant="tonal" icon="check_circle" data-testid="pay-got" @click.stop="ask('got')">{{ T.got() }}</Button>
    <Button v-if="canNot" small variant="text" data-testid="pay-not" @click.stop="ask('not')">{{ T.notGot() }}</Button>
    <Button v-if="invFeature('inv_chamber') && a.payState === 'paid' && a.invoiceId" small variant="text" icon="list_alt" data-testid="pay-receipt" @click.stop="receiptId = a.invoiceId">{{ IT.seeReceipt() }}</Button>
    <JoinCall :a="a" provider :name="name" small />
  </div>
  <ReceiptDialog v-if="receipt" :inv="receipt" @close="receipt = null" />
  <InvoiceByIdDialog v-if="receiptId" :id="receiptId" @close="receiptId = null" />
  <ConfirmDialog
    v-if="asking === 'got'"
    :title="T.gotTitle()"
    :text="T.gotText() + (a.payRef ? ' TrxID: ' + a.payRef : '')"
    :confirm="T.got()"
    :busy="busy"
    :error="err"
    @confirm="act"
    @close="() => !busy && (asking = null)"
  />
  <Dialog v-if="asking === 'not'" size="small" :title="T.notGotTitle()" @close="() => !busy && (asking = null)">
    <p class="t-body-m muted">{{ T.notGotText() }}</p>
    <p v-if="a.payRef" class="t-body-m">TrxID: <span class="appt-trx">{{ a.payRef }}</span></p>
    <TextInput :label="T.reasonLabel()" :placeholder="T.reasonPh()" data-testid="reject-reason" :model-value="reason" @update:model-value="(v) => (reason = v.slice(0, 200))" />
    <ErrorText :text="err" />
    <template #actions>
      <Button variant="text" :disabled="busy" @click="asking = null">{{ tr('Cancel', 'বাতিল') }}</Button>
      <Button variant="danger" :busy="busy" data-testid="reject-confirm" @click="act">{{ T.notGot() }}</Button>
    </template>
  </Dialog>
</template>
