<script setup lang="ts">
// অ্যাপের AcceptOrderDialog: মোট দাম আর কখন পৌঁছাবে (দুটোই ঐচ্ছিক)
import { ref } from 'vue';
import { Button, Dialog, ErrorText, Icon, TextInput } from '@/ui/kit';
import { tr } from '@/lib/i18n';
import type { PharmOrder } from './model';
import { useOrderUpdate } from './online';

const props = defineProps<{ order: PharmOrder }>();
const emit = defineEmits<{ close: [] }>();
const { busyId, err, update } = useOrderUpdate();
const price = ref('');
const eta = ref('');
async function accept() {
  if (await update(props.order, 'accepted', { price: price.value.trim(), eta: eta.value.trim() })) emit('close');
}
</script>

<template>
  <Dialog :title="tr('Accept order', 'অর্ডার নিন')" @close="emit('close')">
    <div class="col gap-4">
      <span class="t-title-m">{{ order.patientName || '—' }}</span>
      <span v-if="order.address" class="icon-line"><Icon name="location_on" /><span>{{ order.address }}</span></span>
      <span v-if="order.deliveryFee" class="icon-line"><Icon name="local_shipping" /><span>{{ tr('Delivery charge: ', 'ডেলিভারি চার্জ: ') }}{{ order.deliveryFee }}</span></span>
    </div>
    <form class="col gap-12" @submit.prevent="accept">
      <TextInput v-model="price" :label="tr('Total price (optional)', 'মোট দাম (ঐচ্ছিক)')" maxlength="40" :placeholder="tr('e.g. Tk 640', 'যেমন: ৬৪০ টাকা')" />
      <TextInput v-model="eta" :label="tr('When will it arrive (optional)', 'কখন পৌঁছাবে (ঐচ্ছিক)')" maxlength="60" :placeholder="tr('e.g. by 7 PM today', 'যেমন: আজ সন্ধ্যা ৭টার মধ্যে')" />
      <button type="submit" hidden />
    </form>
    <p class="t-body-s muted">{{ tr('The patient pays you on delivery.', 'রোগী ওষুধ পেয়ে আপনাকে দাম দেবেন।') }}</p>
    <ErrorText :text="err" />
    <template #actions>
      <Button variant="text" @click="emit('close')">{{ tr('Cancel', 'বাতিল') }}</Button>
      <Button :busy="!!busyId" icon="check_circle" @click="accept">{{ tr('Accept', 'নিলাম') }}</Button>
    </template>
  </Dialog>
</template>
