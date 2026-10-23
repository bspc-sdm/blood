<script setup lang="ts">
// রসিদ বাতিল (invoice_void): কারণ ঠিক করা তালিকা থেকে + লেখা (নিরাপত্তা পর্যালোচনা I2) — মোছে না, "বাতিল" হয়ে থাকে।
// ফার্মেসিতে জিনিস স্টকে ফেরে। ডেস্কটপে কাউন্টারের পিন হলে আগে মালিকের পিন (I7)।
// সার্ভারে reason = "<তালিকার কারণ> — <লেখা>", সাথে reasonCode (wrong_item | returned | wrong_price | other)।
import { computed, ref } from 'vue';
import { Button, Dialog, ErrorText, TextInput, toast } from '@/ui/kit';
import { tr } from '@/lib/i18n';
import { T } from './text';
import { money, type Invoice } from './model';
import { errText, useInvoiceRefresh, voidInvoice } from './actions';
import { ownerGate } from './offline';

const props = defineProps<{ inv: Invoice }>();
const emit = defineEmits<{ close: []; done: [inv: Invoice | null] }>();
const refresh = useInvoiceRefresh();
type Code = 'wrong_item' | 'returned' | 'wrong_price' | 'other';
const reasons = computed<{ value: Code; label: string }[]>(() => [
  { value: 'wrong_item', label: props.inv.kind === 'pharmacy' ? T.vrWrongItem() : T.vrWrongEntry() },
  { value: 'returned', label: props.inv.kind === 'pharmacy' ? T.vrReturned() : T.vrRefunded() },
  { value: 'wrong_price', label: T.vrWrongPrice() },
  { value: 'other', label: T.vrOther() },
]);
const code = ref<Code | null>(null);
const note = ref('');
const busy = ref(false);
const err = ref<string | null>(null);
const ok = computed(() => !!code.value && note.value.trim().length >= 3);
async function save() {
  if (!ok.value) return;
  busy.value = true;
  err.value = null;
  try {
    await ownerGate();
    const label = reasons.value.find((r) => r.value === code.value)!.label;
    const r = await voidInvoice(props.inv, `${label} — ${note.value.trim()}`, { reasonCode: code.value });
    toast(T.voidDone());
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
  <Dialog size="small" :title="T.voidAsk()" @close="() => !busy && emit('close')">
    <span class="t-body-m">{{ inv.no }} · {{ money(inv.total) }}</span>
    <span class="t-body-s muted">{{ T.voidNote() }}<template v-if="inv.kind === 'pharmacy'">{{ ' ' + T.voidStock() }}</template></span>
    <div class="col gap-4">
      <span class="t-label-m">{{ T.voidWhyPick() }}</span>
      <div class="chips" role="radiogroup" :aria-label="T.voidWhyPick()">
        <button v-for="r in reasons" :key="r.value" type="button" class="chip" role="radio" :aria-checked="code === r.value" :aria-pressed="code === r.value" :data-reason="r.value" @click="code = r.value">{{ r.label }}</button>
      </div>
    </div>
    <TextInput v-model="note" :label="T.voidWhy()" maxlength="160" data-testid="void-reason" />
    <ErrorText :text="err" />
    <template #actions>
      <Button variant="text" :disabled="busy" @click="emit('close')">{{ tr('Back', 'ফিরে যান') }}</Button>
      <Button variant="danger" :busy="busy" :disabled="!ok" data-testid="void-confirm" @click="save">{{ T.voidIt() }}</Button>
    </template>
  </Dialog>
</template>
