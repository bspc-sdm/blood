<script setup lang="ts">
// ব্যবসার দিক: একটা অর্ডার/পরীক্ষার জন্য রোগীর কাছে পেমেন্ট চাওয়া (pay_request_create {refType, refId, purpose}) —
// টাকা সার্ভার নিজে জিনিস থেকে নেয় (অর্ডারের লাইন + ডেলিভারি, বিল — P6)। আগে থেকে থাকলে তার অবস্থা আর "পেমেন্ট" পাতার লিংক;
// নম্বর না থাকলে (no_methods) সেটিংয়ে পাঠায়।
// নগদ সবসময় চলে — এটা ঐচ্ছিক। কোন সুইচে দেখাবে তা ডাকার জায়গা ঠিক করে (payOn)।
import { computed, ref } from 'vue';
import { RouterLink } from 'vue-router';
import { Button, Dialog, ErrorText, Icon, Tag, toast } from '@/ui/kit';
import { ServerError, errorText } from '@/lib/api';
import { tr } from '@/lib/i18n';
import { useQ } from '@/lib/query';
import * as api from './api';
import type { BizKind, PayPurpose, PayRequest } from './model';
import { T, payErrText, statusTextBiz, statusTone } from './text';

const props = defineProps<{ kind: BizKind; bizId: string; refType: string; refId: string; purpose: PayPurpose }>();
const q = useQ<PayRequest | null>(() => ({
  queryKey: ['pay_request_get', 'biz', props.refType, props.refId, props.purpose],
  queryFn: () => api.getRequestByRef(props.refType, props.refId, props.purpose).catch((e) => {
    if (e instanceof ServerError && e.status === 404) return null;
    throw e;
  }),
  retry: false,
  staleTime: 15_000,
}));
const open = ref(false);
const busy = ref(false);
const err = ref<string | null>(null);
const noMethods = ref(false);
const settings = computed(() => `/work/payments/settings?biz=${encodeURIComponent(`${props.kind}:${props.bizId}`)}`);
async function send() {
  busy.value = true;
  err.value = null;
  noMethods.value = false;
  try {
    await api.createRequest(props.refType, props.refId, props.purpose);
    toast(T.askPaySent());
    open.value = false;
    void q.refetch();
    void api.invalidatePay();
  } catch (e) {
    const code = e instanceof ServerError ? e.code : undefined;
    noMethods.value = code === 'no_methods' || code === 'pay_no_methods';
    err.value = payErrText(code, 'bkash', errorText(e));
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <div v-if="!q.isPending" class="row wrap gap-6 pay-entry" data-testid="ask-pay">
    <template v-if="q.data">
      <Tag :tone="statusTone(q.data.status)" data-testid="ask-pay-state">{{ statusTextBiz(q.data.status) }}</Tag>
      <RouterLink :to="`/work/payments/all?biz=${encodeURIComponent(`${kind}:${bizId}`)}`" class="btn text small has-icon"><Icon name="payments" />{{ T.seePayment() }}</RouterLink>
    </template>
    <Button v-else small variant="outline" icon="payments" data-testid="ask-pay-open" @click="open = true">{{ T.askPay() }}</Button>
    <Dialog v-if="open" size="small" :title="T.askPayTitle()" @close="() => !busy && (open = false)">
      <p class="t-body-m muted">{{ T.askPayText() }}</p>
      <p class="t-body-s muted" data-testid="ask-pay-amount-note">{{ T.amountFromSource() }}</p>
      <ErrorText :text="err" />
      <RouterLink v-if="noMethods" :to="settings" class="btn tonal small">{{ T.setMethodsFirst() }}</RouterLink>
      <template #actions>
        <Button variant="text" :disabled="busy" @click="open = false">{{ tr('Cancel', 'বাতিল') }}</Button>
        <Button :busy="busy" data-testid="ask-pay-send" @click="send">{{ T.askPay() }}</Button>
      </template>
    </Dialog>
  </div>
</template>
