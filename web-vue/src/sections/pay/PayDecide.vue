<script setup lang="ts">
// ব্যবসা: "পেয়েছি" আর "পাইনি" (কারণ: টাকা আসেনি / পরিমাণ মেলেনি / অন্য নম্বরে / অন্য — pay_reject)।
// "পেয়েছি" (pay_confirm, রসিদ তৈরি হয়): বড় করে টাকা, TrxID, মাধ্যম, শেষ ৪ অঙ্ক আর সময়, তিনটা টিক (TrxID পেয়েছি, টাকা মিলেছে,
// শেষ ৪ মিলেছে) — তিনটাই না দিলে বোতাম বন্ধ; টিকগুলো `checks` হয়ে সার্ভারে যায় (নিরাপত্তা পর্যালোচনা P1)।
// অনলাইন চেম্বারের আগের পথ (TrxID ঐচ্ছিক, শেষ ৪ নেই): claim ছাড়া — আগের মতো একটা "নিশ্চিত?" ধাপ, "পাইনি"-তে নিজের লেখা কারণ।
import { computed, ref } from 'vue';
import { Button, Check, ChoiceChips, ConfirmDialog, Dialog, ErrorText, TextInput } from '@/ui/kit';
import { errorText } from '@/lib/api';
import { num, tr } from '@/lib/i18n';
import { formatDateTime } from '@/lib/format';
import { REJECT_REASONS, type PayClaim, type RejectReason } from './model';
import { T, methodName, rejectText } from './text';

export interface DecideTexts { confirmTitle: string; confirmText: string; confirmLabel: string; rejectTitle: string; rejectText: string; rejectLabel: string; reasonLabel: string; reasonPh: string; trx?: string }
export interface ConfirmChecks { trxId: boolean; amount: boolean; last4: boolean }
const props = defineProps<{
  mode: 'confirm' | 'reject';
  texts: DecideTexts;
  /** নতুন পথ: রোগীর জানানো (থাকলে তিন টিকের ধাপ) */
  claim?: PayClaim | null;
  amountText?: string;
  /** আগের পথ: "পাইনি"-তে কোড নয়, নিজের লেখা কারণ (ঐচ্ছিক) */
  freeReason?: boolean;
  onConfirm: (checks: ConfirmChecks | null) => Promise<void>;
  onReject: (reason: RejectReason, note: string) => Promise<void>;
}>();
const emit = defineEmits<{ close: []; done: [] }>();
const reason = ref<RejectReason>('not_received');
const note = ref('');
const busy = ref(false);
const err = ref<string | null>(null);
const checks = ref<ConfirmChecks>({ trxId: false, amount: false, last4: false });
const allChecked = computed(() => checks.value.trxId && checks.value.amount && checks.value.last4);
const needNote = computed(() => !props.freeReason && reason.value === 'other' && !note.value.trim());
async function run(f: () => Promise<void>) {
  busy.value = true;
  err.value = null;
  try {
    await f();
    emit('done');
  } catch (e) {
    err.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
function confirm() {
  if (props.claim && !allChecked.value) return void (err.value = T.checksNeeded());
  void run(() => props.onConfirm(props.claim ? { ...checks.value } : null));
}
function reject() {
  if (needNote.value) return void (err.value = T.rejectNeedNote());
  void run(() => props.onReject(reason.value, note.value.trim()));
}
</script>

<template>
  <!-- নতুন পথ: তিন টিক -->
  <Dialog v-if="mode === 'confirm' && claim" size="small" :title="texts.confirmTitle" @close="() => !busy && emit('close')">
    <div class="col gap-4" data-testid="confirm-claim">
      <span v-if="amountText" class="pay-amount num">{{ amountText }}</span>
      <span class="pay-claim-big">{{ methodName(claim.method) }} · TrxID <span class="pay-trx">{{ claim.trxId }}</span></span>
      <span class="t-body-m">{{ T.last4() }}: <b class="num">{{ num(claim.senderLast4) }}</b></span>
      <span v-if="claim.at" class="t-body-s muted">{{ T.claimedAt(formatDateTime(claim.at)) }}</span>
      <span v-if="claim.byName" class="t-body-s muted">{{ T.sentBy(claim.byName) }}</span>
    </div>
    <p class="t-body-m muted">{{ T.checkInApp() }}</p>
    <div class="pay-checks">
      <Check v-model="checks.trxId" data-testid="check-trx">{{ T.checkTrx() }}</Check>
      <Check v-model="checks.amount" data-testid="check-amount">{{ T.checkAmount(amountText ?? '') }}</Check>
      <Check v-model="checks.last4" data-testid="check-last4">{{ T.checkLast4(num(claim.senderLast4)) }}</Check>
    </div>
    <ErrorText :text="err" />
    <template #actions>
      <Button variant="text" :disabled="busy" @click="emit('close')">{{ tr('Cancel', 'বাতিল') }}</Button>
      <Button icon="check_circle" :busy="busy" :disabled="!allChecked" data-testid="confirm-go" @click="confirm">{{ texts.confirmLabel }}</Button>
    </template>
  </Dialog>
  <!-- আগের পথ: একটা "নিশ্চিত?" -->
  <ConfirmDialog
    v-else-if="mode === 'confirm'"
    :title="texts.confirmTitle"
    :text="texts.confirmText"
    :confirm="texts.confirmLabel"
    :busy="busy"
    :error="err"
    @confirm="confirm"
    @close="() => !busy && emit('close')"
  />
  <Dialog v-else size="small" :title="texts.rejectTitle" @close="() => !busy && emit('close')">
    <p class="t-body-m muted">{{ texts.rejectText }}</p>
    <p v-if="texts.trx" class="t-body-m">TrxID: <span class="pay-trx">{{ texts.trx }}</span></p>
    <template v-if="!freeReason">
      <span class="t-label-l">{{ T.rejectWhy() }}</span>
      <ChoiceChips v-model="reason" :options="REJECT_REASONS.map((r) => ({ value: r, label: rejectText(r) }))" data-testid="reject-code" />
    </template>
    <TextInput
      :label="freeReason ? texts.reasonLabel : reason === 'other' ? T.rejectNoteReq() : T.rejectNote()"
      :placeholder="freeReason ? texts.reasonPh : T.rejectNotePh()"
      :data-testid="freeReason ? 'reject-reason' : 'reject-note'"
      :model-value="note"
      @update:model-value="(v: string) => (note = v.slice(0, 200))"
    />
    <ErrorText :text="err" />
    <template #actions>
      <Button variant="text" :disabled="busy" @click="emit('close')">{{ tr('Cancel', 'বাতিল') }}</Button>
      <Button variant="danger" :busy="busy" data-testid="reject-confirm" @click="reject">{{ texts.rejectLabel }}</Button>
    </template>
  </Dialog>
</template>
