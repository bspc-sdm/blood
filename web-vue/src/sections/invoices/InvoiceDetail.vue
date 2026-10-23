<script setup lang="ts">
// একটা রসিদ (রোগী): তালিকায় থাকলে সেখান থেকে, নইলে invoice_get (নোটিফিকেশন বা অর্ডারের লিংক থেকে সরাসরি খুললে)
import { computed, ref } from 'vue';
import { Button, ErrorBox, SkeletonCards, Tag } from '@/ui/kit';
import DetailPane from '@/shell/DetailPane.vue';
import { errorText } from '@/lib/api';
import { useCall } from '@/lib/query';
import { T } from './text';
import ReceiptView from './ReceiptView.vue';
import OverchargeDialog from './OverchargeDialog.vue';
import { pickInvoice, type Invoice } from './model';
import RefPay from '../pay/RefPay.vue';
import { payOn } from '../pay/features';
import { startDue } from '../pay/api';
import { T as PT } from '../pay/text';

const props = defineProps<{ id: string; initial: Invoice | null; back: () => void }>();
const q = useCall<any>('invoice_get', () => ({ id: props.id }), () => ({ enabled: !props.initial }));
const inv = computed(() => props.initial ?? (q.data ? pickInvoice(q.data) : null));
const reporting = ref(false);
</script>

<template>
  <DetailPane :title="T.receipt()" :on-back="back">
    <template v-if="inv">
      <!-- অ্যাম্বুলেন্স: ভাড়ার তালিকার চেয়ে বেশি হলে সার্ভারের overFare; রোগী রসিদের চেয়ে বেশি দিলে রিপোর্ট (নিরাপত্তা পর্যালোচনা I8) -->
      <div v-if="inv.kind === 'ambulance'" class="row wrap gap-8">
        <Tag v-if="inv.overFare" tone="error">{{ T.overFare() }}</Tag>
        <span class="grow" />
        <Button small variant="danger-text" icon="flag" data-testid="over-report" @click="reporting = true">{{ T.overchargedVsReceipt() }}</Button>
      </div>
      <!-- বাকি পরিশোধ (pay_due): রসিদের বাকি টাকা ব্যবসার নম্বরে -->
      <div v-if="inv.status !== 'void' && inv.due > 0 && inv.id && payOn('pay_due')" class="row"><RefPay ref-type="invoice" :ref-id="inv.id" purpose="invoice_due" :start="() => startDue(inv!.id)" :label="PT.payDue()" /></div>
      <ReceiptView :inv="inv" />
      <OverchargeDialog v-if="reporting" :inv="inv" @close="reporting = false" />
    </template>
    <SkeletonCards v-else-if="q.isPending" :n="1" :h="320" />
    <ErrorBox v-else :error="q.error ? errorText(q.error) : T.verifyNotFound()" :on-retry="() => q.refetch()" />
  </DetailPane>
</template>
