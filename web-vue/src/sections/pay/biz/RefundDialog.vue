<script setup lang="ts">
// ব্যবসা: টাকা ফেরত (pay_refund {id, trxId, amount?}) — আগে নিজের বিকাশ/নগদ থেকে রোগীকে পাঠিয়ে সেই TrxID লেখা।
// পুরো টাকা ফেরত দিলে সার্ভার রসিদ বাতিল করে; রোগী "ফেরত পেয়েছি" জানান।
import { computed, ref } from 'vue';
import { Button, Dialog, ErrorText, TextInput, toast } from '@/ui/kit';
import { errorText } from '@/lib/api';
import { num, toAsciiDigits, tr } from '@/lib/i18n';
import { money } from '../../invoices/model';
import * as api from '../api';
import type { PayRequest } from '../model';
import { T } from '../text';

const props = defineProps<{ r: PayRequest; extra: Record<string, unknown> }>();
const emit = defineEmits<{ close: []; done: [] }>();
const amount = ref(String(Math.max(0, props.r.amount - (props.r.refund?.amount ?? 0))));
const trx = ref('');
const busy = ref(false);
const err = ref<string | null>(null);
const a = computed(() => Number(toAsciiDigits(amount.value).replace(/[^0-9.]/g, '')));
// আগে ফেরত দেওয়া বাদ দিয়ে (আংশিক ফেরত; সার্ভারও দেখে — P5)
const max = computed(() => Math.max(0, props.r.amount - (props.r.refund?.amount ?? 0)));
async function save() {
  if (!(a.value > 0 && a.value <= max.value + 0.001)) return void (err.value = T.errRefundAmount(money(max.value)));
  if (!/^[A-Z0-9]{6,20}$/.test(trx.value)) return void (err.value = T.errRefundTrx());
  busy.value = true;
  err.value = null;
  try {
    await api.refundPay(props.r.id, trx.value, a.value < props.r.amount ? a.value : null, props.extra);
    toast(T.refundDone());
    emit('done');
  } catch (e) {
    err.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <Dialog size="small" :title="T.refundTitle()" @close="() => !busy && emit('close')">
    <p class="t-body-m">{{ (r.payerName ? r.payerName + ' · ' : '') + money(r.amount) }}</p>
    <p class="t-body-s muted">{{ T.refundHow() }}</p>
    <p v-if="r.claim?.senderLast4" class="t-body-m semi" data-testid="refund-to-sender">{{ T.refundToSender(num(r.claim.senderLast4)) }}</p>
    <TextInput :label="T.refundAmount()" inputmode="decimal" data-testid="refund-amount" :model-value="amount" @update:model-value="(v: string) => (amount = toAsciiDigits(v).replace(/[^0-9.]/g, '').slice(0, 10))" />
    <TextInput
      :label="T.refundTrxLabel()"
      autocomplete="off"
      autocapitalize="characters"
      spellcheck="false"
      maxlength="20"
      data-testid="refund-trx"
      :model-value="trx"
      @update:model-value="(v: string) => (trx = toAsciiDigits(v).toUpperCase().replace(/[^0-9A-Z]/g, '').slice(0, 20))"
    />
    <p class="t-body-s muted">{{ T.refundAckNote() }}</p>
    <ErrorText :text="err" />
    <template #actions>
      <Button variant="text" :disabled="busy" @click="emit('close')">{{ tr('Cancel', 'বাতিল') }}</Button>
      <Button variant="danger" :busy="busy" data-testid="refund-save" @click="save">{{ T.refund() }}</Button>
    </template>
  </Dialog>
</template>
