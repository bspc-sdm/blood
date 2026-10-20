<script setup lang="ts">
// দোকান থেকে প্রেসক্রিপশন ফিরিয়ে নেওয়া
import { computed, ref } from 'vue';
import { Button, ChoiceChips, Dialog, ErrorText } from '@/ui/kit';
import { call, errorText } from '@/lib/api';
import { tr } from '@/lib/i18n';
import type { RxOrder } from './rxactivity';

const props = defineProps<{ o: RxOrder }>();
const emit = defineEmits<{ close: []; done: [] }>();
const why = ref<'na' | 'other'>('na');
const busy = ref(false);
const err = ref<string | null>(null);
const opts = computed(() => [
  { value: 'na' as const, label: tr("They don't have the medicines", 'ওদের কাছে ওষুধ নেই') },
  { value: 'other' as const, label: tr('Another reason', 'অন্য কারণ') },
]);
async function go() {
  busy.value = true;
  err.value = null;
  try {
    await call('rx_order_withdraw', { id: props.o.id, reason: why.value === 'na' ? 'not_available' : 'other' });
    emit('done');
  } catch (e) {
    err.value = errorText(e);
    busy.value = false;
  }
}
</script>

<template>
  <Dialog size="small" :title="tr(`Take back from ${o.pharmacyName}?`, `${o.pharmacyName} থেকে ফিরিয়ে নেবেন?`)" @close="!busy && emit('close')">
    <span class="t-label-l">{{ tr('Why?', 'কেন?') }}</span>
    <div class="health-stack-chips">
      <ChoiceChips v-model="why" :options="opts" />
    </div>
    <span class="t-body-s muted">{{ tr('Medicines not bought yet stay on the prescription — send it to another shop.', 'যে ওষুধ কেনা বাকি, তা প্রেসক্রিপশনে থাকবে — অন্য দোকানে পাঠান।') }}</span>
    <ErrorText :text="err" />
    <template #actions>
      <Button variant="text" :disabled="busy" @click="emit('close')">{{ tr('Cancel', 'বাতিল') }}</Button>
      <Button :busy="busy" @click="go">{{ tr('Take back', 'ফিরিয়ে নিন') }}</Button>
    </template>
  </Dialog>
</template>
