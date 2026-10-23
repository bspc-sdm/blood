<script setup lang="ts">
// রোগী: অনলাইন অ্যাপয়েন্টমেন্টের ফি — ডাক্তারের bKash নম্বর (কপি), ধাপ, "টাকা পাঠিয়েছি" (+ ঐচ্ছিক TrxID → appt_pay_claim),
// অবস্থা: পাঠানো হয়নি / যাচাই হচ্ছে / ফি পরিশোধিত / ডাক্তার পাননি (আবার জানানো যায়)।
import { computed, ref } from 'vue';
import { Button, ErrorText, InfoText, Tag, TextInput, toast, useNow } from '@/ui/kit';
import { ServerError, call, errorText } from '@/lib/api';
import { num, tr } from '@/lib/i18n';
import type { Appointment } from '../model';
import { T, TRX_LEN, canReportNoShow, payLabel, payTone, trxInput, trxOk } from './index';
import ReasonReportDialog from '../ReasonReportDialog.vue';

const props = defineProps<{ a: Appointment }>();
const emit = defineEmits<{ changed: [] }>();
const trx = ref('');
const busy = ref(false);
const err = ref<string | null>(null);
const copied = ref(false);
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
const st = computed(() => props.a.payState);
// ফি পাঠানো যায়: ডাক্তার নিশ্চিত করেছেন, এখনো মেলেনি (পাঠানো হয়নি বা ডাক্তার পাননি)
const canClaim = computed(() => props.a.status === 'confirmed' && (st.value === 'unpaid' || st.value === 'rejected'));
const showNumber = computed(() => !!props.a.bkashNumber && st.value !== 'paid' && props.a.status === 'confirmed');

function copy() {
  navigator.clipboard?.writeText(props.a.bkashNumber).then(
    () => {
      copied.value = true;
      toast(T.copied());
    },
    () => (copied.value = false),
  );
}
async function claim() {
  const ref_ = trxInput(trx.value);
  if (!trxOk(ref_)) {
    err.value = T.errTrx();
    return;
  }
  busy.value = true;
  err.value = null;
  try {
    await call('appt_pay_claim', { appointmentId: props.a.id, ...(ref_ ? { payRef: ref_ } : {}) });
    toast(T.sentToast());
    trx.value = '';
    emit('changed');
  } catch (e) {
    // একই TrxID আবার: 409
    // R7: একই TrxID এই ডাক্তারের কাছে আগে (409 pay_ref_used), ঘণ্টায় ৫ বারের বেশি (429 pay_claim_rate)
    const code = e instanceof ServerError ? e.code : undefined;
    err.value = code === 'pay_ref_used' ? T.trxUsed() : code === 'pay_claim_rate' ? T.claimRate() : errorText(e);
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <section class="card col gap-12 appt-pay" data-testid="online-pay" :data-pay="a.noFee ? 'nofee' : st">
    <div class="row">
      <h3 class="t-title-s semi grow">{{ T.payTitle() }}</h3>
      <Tag v-if="!a.noFee" :tone="payTone(st)" data-testid="pay-state">{{ payLabel(st) }}</Tag>
    </div>
    <p v-if="a.noFee" class="t-body-l">{{ tr('No fee', 'ফি নেই') }}</p>
    <template v-else>
      <p v-if="a.fee" class="t-title-m">{{ tr('Fee: ', 'ফি: ') + a.fee }}</p>
      <InfoText v-if="a.status === 'pending'" :text="T.afterConfirm()" />
      <div v-if="showNumber" class="appt-bkash">
        <span class="col gap-2 grow">
          <span class="t-label-m muted">{{ T.bkash() }}</span>
          <span class="t-title-l num" data-testid="bkash-shown">{{ num(a.bkashNumber) }}</span>
        </span>
        <button type="button" class="btn tonal small" data-testid="bkash-copy" @click="copy">{{ copied ? tr('Copied', 'কপি হয়েছে') : tr('Copy', 'কপি') }}</button>
      </div>
      <template v-if="st === 'claimed'">
        <InfoText :text="T.checking()" />
        <p v-if="a.payRef" class="t-body-m">TrxID: <span class="appt-trx">{{ a.payRef }}</span></p>
      </template>
      <p v-if="st === 'paid' && a.payRef" class="t-body-m muted">TrxID: <span class="appt-trx">{{ a.payRef }}</span></p>
      <template v-if="canClaim">
        <div v-if="st === 'rejected'" class="col gap-4 appt-pay-rej" role="status">
          <p class="t-body-m">{{ T.rejected() }}</p>
          <p v-if="a.payReason" class="t-body-s">{{ T.reason() + a.payReason }}</p>
        </div>
        <div class="col gap-4">
          <span class="t-label-l">{{ T.howTo() }}</span>
          <ol class="appt-steps t-body-m">
            <li>{{ T.step1() }}</li>
            <li>{{ T.step2() }}</li>
            <li>{{ T.step3() }}</li>
            <li>{{ T.step4() }}</li>
          </ol>
        </div>
        <TextInput
          :label="T.trx()"
          :hint="T.trxHint()"
          :maxlength="TRX_LEN"
          :placeholder="T.trxPh()"
          autocomplete="off"
          autocapitalize="characters"
          spellcheck="false"
          data-testid="trx-input"
          :model-value="trx"
          @update:model-value="(v) => (trx = trxInput(v))"
        />
        <ErrorText :text="err" />
        <div class="row">
          <Button icon="check_circle" :busy="busy" data-testid="pay-claim" @click="claim">{{ T.sent() }}</Button>
        </div>
      </template>
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
