<script setup lang="ts">
// রোগী: ডাক্তারের নম্বরে টাকা পাঠিয়ে TrxID
import { computed, ref } from 'vue';
import { Button, ChoiceChips, Dialog, ErrorText, TextInput } from '@/ui/kit';
import { call, errorText } from '@/lib/api';
import { num, tr } from '@/lib/i18n';
import { payLabel, type RxReview } from './rxactivity';

const props = defineProps<{ r: RxReview }>();
const emit = defineEmits<{ close: []; done: [] }>();
const method = ref(props.r.payMethods[0] ?? '');
const trx = ref('');
const busy = ref(false);
const err = ref<string | null>(null);
const opts = computed(() => props.r.payMethods.map((m) => ({ value: m, label: payLabel(m) })));
async function send() {
  busy.value = true;
  err.value = null;
  try {
    await call('review_pay', { id: props.r.id, method: method.value, trxId: trx.value });
    emit('done');
  } catch (e) {
    err.value = errorText(e);
    busy.value = false;
  }
}
</script>

<template>
  <Dialog :title="tr('Payment for showing the report', 'রিপোর্ট দেখানোর টাকা')" @close="!busy && emit('close')">
    <p class="t-body-l">{{ tr(`Send ${r.fee} to ${r.payNumber} with `, `${r.fee} পাঠান ${num(r.payNumber)} নম্বরে — `) + r.payMethods.map(payLabel).join(' / ') }}</p>
    <span class="t-body-s muted">{{ tr("The money goes straight to the doctor. Lifolo doesn't hold or take any of it.", 'টাকা সরাসরি ডাক্তারের কাছে যায়। Lifolo টাকা রাখে না, কিছু কাটে না।') }}</span>
    <ChoiceChips v-if="r.payMethods.length > 1" v-model="method" :options="opts" />
    <TextInput
      :label="tr('Transaction ID (TrxID)', 'ট্রানজেকশন আইডি (TrxID)')"
      :model-value="trx"
      autocomplete="off"
      :spellcheck="false"
      @update:model-value="(v: string) => (trx = v.replace(/[^A-Za-z0-9]/g, '').slice(0, 30))"
    />
    <ErrorText :text="err" />
    <template #actions>
      <Button variant="text" :disabled="busy" @click="emit('close')">{{ tr('Cancel', 'বাতিল') }}</Button>
      <Button :busy="busy" :disabled="trx.length < 6" @click="send">{{ tr('Send', 'পাঠান') }}</Button>
    </template>
  </Dialog>
</template>
