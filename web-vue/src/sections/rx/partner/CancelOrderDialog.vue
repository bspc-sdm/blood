<script setup lang="ts">
// "পারব না" — কারণ (রোগী দেখেন)
import { ref } from 'vue';
import { Button, Dialog, ErrorText, TextInput } from '@/ui/kit';
import { tr } from '@/lib/i18n';
import type { PharmOrder } from './model';
import { useOrderUpdate } from './online';

const props = defineProps<{ order: PharmOrder }>();
const emit = defineEmits<{ close: [] }>();
const { busyId, err, update } = useOrderUpdate();
const reason = ref(tr('Medicines not available', 'ওষুধ নেই'));
async function go() {
  if (await update(props.order, 'cancelled', { reason: reason.value.trim() })) emit('close');
}
</script>

<template>
  <Dialog size="small" :title="tr('Can\'t provide the medicines?', 'ওষুধ দিতে পারবেন না?')" @close="emit('close')">
    <p class="t-body-m">{{ order.patientName || '—' }}</p>
    <TextInput v-model="reason" :label="tr('Reason (the patient sees it)', 'কারণ (রোগী দেখবেন)')" maxlength="200" />
    <ErrorText :text="err" />
    <template #actions>
      <Button variant="text" @click="emit('close')">{{ tr('Cancel', 'বাতিল') }}</Button>
      <Button variant="danger" :busy="!!busyId" @click="go">{{ tr('Can\'t', 'পারব না') }}</Button>
    </template>
  </Dialog>
</template>
