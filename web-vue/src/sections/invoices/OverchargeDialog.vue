<script setup lang="ts">
// অ্যাম্বুলেন্সের রসিদে রোগী: "রসিদের চেয়ে বেশি নিয়েছে" (নিরাপত্তা পর্যালোচনা I8) — আসলে কত দিয়েছেন আর কী হয়েছে।
// invoice_report {id, paid, note} → অ্যাডমিন দেখেন।
import { computed, ref } from 'vue';
import { Button, Dialog, ErrorText, TextArea, inputValue, toast } from '@/ui/kit';
import { call, errorText } from '@/lib/api';
import { tr } from '@/lib/i18n';
import { numInput, readNum } from '@/sections/rx/partner/books/model';
import { T } from './text';
import { money, type Invoice } from './model';

const props = defineProps<{ inv: Invoice }>();
const emit = defineEmits<{ close: [] }>();
const paid = ref('');
const note = ref('');
const busy = ref(false);
const err = ref<string | null>(null);
const p = computed(() => readNum(paid.value));
const ok = computed(() => p.value != null && !Number.isNaN(p.value) && p.value > props.inv.total);
async function submit() {
  if (!ok.value) return;
  busy.value = true;
  err.value = null;
  try {
    await call('invoice_report', { id: props.inv.id, paid: p.value, ...(note.value.trim() ? { note: note.value.trim() } : {}) });
    toast(tr('Reported. An admin will review it.', 'রিপোর্ট করা হয়েছে। অ্যাডমিন দেখবেন।'));
    emit('close');
  } catch (e) {
    err.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <Dialog size="small" :title="T.overchargedVsReceipt()" @close="!busy && emit('close')">
    <span class="t-body-m">{{ T.receipt() }} {{ inv.no }} · {{ money(inv.total) }}</span>
    <label class="bk-cell">
      <span class="t-label-s muted">{{ T.paidActually() }}</span>
      <input class="input num" inputmode="decimal" :value="paid" data-testid="over-paid" @input="paid = inputValue($event, numInput)" />
    </label>
    <TextArea v-model="note" :label="tr('What happened', 'কী হয়েছে')" rows="3" maxlength="200" />
    <ErrorText :text="err" />
    <template #actions>
      <Button variant="text" :disabled="busy" @click="emit('close')">{{ tr('Cancel', 'বাতিল') }}</Button>
      <Button :busy="busy" :disabled="!ok" data-testid="over-send" @click="submit">{{ tr('Report', 'রিপোর্ট') }}</Button>
    </template>
  </Dialog>
</template>
