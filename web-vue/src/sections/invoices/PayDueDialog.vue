<script setup lang="ts">
// বাকি আদায় (invoice_pay): কত (মূল: পুরো বাকি), মাধ্যম, লেনদেনের আইডি
import { computed, ref } from 'vue';
import { Button, Dialog, ErrorText, inputValue, toast } from '@/ui/kit';
import { tr } from '@/lib/i18n';
import { numInput, readNum } from '@/sections/rx/partner/books/model';
import { T } from './text';
import PayFields from './PayFields.vue';
import { money, type Invoice, type PayMethod } from './model';
import { errText, payDue, useInvoiceRefresh } from './actions';

const props = defineProps<{ inv: Invoice }>();
const emit = defineEmits<{ close: []; done: [inv: Invoice | null] }>();
const refresh = useInvoiceRefresh();
const amount = ref(String(props.inv.due));
const method = ref<PayMethod>('cash');
const txRef = ref('');
const busy = ref(false);
const err = ref<string | null>(null);
const a = computed(() => readNum(amount.value));
const ok = computed(() => a.value != null && !Number.isNaN(a.value) && a.value > 0 && a.value <= props.inv.due + 0.001);
async function save() {
  if (!ok.value) return;
  busy.value = true;
  err.value = null;
  try {
    const r = await payDue(props.inv, a.value!, method.value, txRef.value);
    toast(T.collected() + ' · ' + money(a.value));
    refresh();
    emit('done', r.invoice);
  } catch (e) {
    err.value = errText(e);
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <Dialog size="small" :title="T.collectDue()" @close="() => !busy && emit('close')">
    <span class="t-body-m">{{ inv.no }} · {{ inv.customer?.name || '' }}</span>
    <span class="t-body-m">{{ T.due() }}: <b class="num">{{ money(inv.due) }}</b></span>
    <label class="bk-cell">
      <span class="t-label-s muted">{{ T.amount() }}</span>
      <input class="input num" inputmode="decimal" :value="amount" data-testid="due-amount" @input="amount = inputValue($event, numInput)" />
    </label>
    <PayFields v-model:method="method" v-model:tx-ref="txRef" :total="a ?? 0" no-given />
    <ErrorText :text="err" />
    <template #actions>
      <Button variant="text" :disabled="busy" @click="emit('close')">{{ tr('Cancel', 'বাতিল') }}</Button>
      <Button icon="check_circle" :busy="busy" :disabled="!ok" data-testid="due-save" @click="save">{{ T.collectDue() }}</Button>
    </template>
  </Dialog>
</template>
