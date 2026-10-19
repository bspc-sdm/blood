<script setup lang="ts">
import { ref } from 'vue';
import { Button, Check, ChoiceChips, Dialog, ErrorText, Switch, TextInput } from '@/ui/kit';
import { call, errorText } from '@/lib/api';
import { tr } from '@/lib/i18n';
import { PAY_METHODS, payLabel, type ReviewSettings } from './model';

const props = defineProps<{ initial: ReviewSettings }>();
const emit = defineEmits<{ close: []; saved: [] }>();
const v = ref<ReviewSettings>({ ...props.initial });
const busy = ref(false);
const err = ref<string | null>(null);
async function save() {
  busy.value = true;
  err.value = null;
  try {
    const x = v.value;
    await call('review_settings', { set: true, enabled: x.enabled, noFee: x.noFee, fee: x.fee.trim(), payNumber: x.payNumber.trim(), payMethods: x.payMethods });
    emit('saved');
  } catch (e) {
    err.value = errorText(e);
  }
  busy.value = false;
}
const upd = (patch: Partial<ReviewSettings>) => (v.value = { ...v.value, ...patch });
</script>

<template>
  <Dialog :title="tr('Online report review', 'অনলাইনে রিপোর্ট দেখা')" @close="() => !busy && emit('close')">
    <Switch :label="tr('Check reports online', 'অনলাইনে রিপোর্ট দেখব')" :model-value="v.enabled" @update:model-value="(x) => upd({ enabled: x })" />
    <template v-if="v.enabled">
      <TextInput :label="tr('Fee', 'ফি')" :model-value="v.fee" :disabled="v.noFee" :placeholder="tr('e.g. 300 taka', 'যেমন: ৩০০ টাকা')" @update:model-value="(x) => upd({ fee: x.slice(0, 40) })" />
      <Check :model-value="v.noFee" @update:model-value="(x) => upd({ noFee: x, fee: x ? '' : v.fee })">{{ tr('No fee', 'ফি চাই না') }}</Check>
      <template v-if="!v.noFee">
        <TextInput :label="tr('Number to receive money', 'যে নম্বরে টাকা নেবেন')" type="tel" inputmode="tel" :model-value="v.payNumber" @update:model-value="(x) => upd({ payNumber: x.slice(0, 20) })" />
        <ChoiceChips multi :options="PAY_METHODS.map((m) => ({ value: m, label: payLabel(m) }))" :model-value="v.payMethods" @update:model-value="(x: string[]) => upd({ payMethods: x })" />
        <p class="t-body-s muted">{{ tr('Patients send money to you directly and enter the TrxID. You or your assistant confirm it; then the report opens for you.', 'রোগী সরাসরি আপনাকে টাকা পাঠিয়ে TrxID দেবেন। আপনি বা সহকারী মিলিয়ে নিশ্চিত করলে তবেই রিপোর্ট খুলবে।') }}</p>
      </template>
    </template>
    <ErrorText :text="err" />
    <template #actions>
      <Button variant="text" :disabled="busy" @click="emit('close')">{{ tr('Cancel', 'বাতিল') }}</Button>
      <Button :busy="busy" @click="save">{{ tr('Save', 'সেভ') }}</Button>
    </template>
  </Dialog>
</template>
