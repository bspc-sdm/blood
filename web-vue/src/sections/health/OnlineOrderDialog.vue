<script setup lang="ts">
// অনলাইন অর্ডার: কোথায় পৌঁছাবে আর ফোন (দাম পৌঁছালে, দোকানকে সরাসরি)
import { ref } from 'vue';
import { Button, Dialog, IconLine, TextArea, TextInput } from '@/ui/kit';
import { call, errorText } from '@/lib/api';
import { tr } from '@/lib/i18n';
import { useSession } from '@/lib/session';
import type { LastHome, Rx } from './rx';
import { pharmEstimateText, type PharmOption } from './rxactivity';
import SendError from './SendError.vue';

const props = defineProps<{ rx: Rx; p: PharmOption; last?: LastHome | null }>();
const emit = defineEmits<{ close: []; sent: [] }>();
const s = useSession();
// আগের অনলাইন অর্ডার/বাসা থেকে নমুনার ঠিকানা-ফোন আগে থেকে (ফার্মেসির তালিকা থেকে এলে — সার্ভারের lastHome)
const address = ref(props.last?.address ?? '');
const phone = ref(props.last?.phone || s.profile?.phone || '');
const note = ref('');
const busy = ref(false);
const err = ref<string | null>(null);
async function order() {
  busy.value = true;
  err.value = null;
  try {
    await call('rx_send_pharmacy', { rxId: props.rx.id, pharmacyId: props.p.id, mode: 'online', address: address.value.trim(), phone: phone.value.trim(), note: note.value.trim() });
    emit('sent');
  } catch (e) {
    err.value = errorText(e);
    busy.value = false;
  }
}
</script>

<template>
  <Dialog :title="p.name" @close="!busy && emit('close')">
    <IconLine v-if="p.deliveryFee" icon="local_shipping">{{ tr('Delivery: ', 'ডেলিভারি: ') + p.deliveryFee }}</IconLine>
    <span v-if="p.keepsStock && p.missing.length" class="t-body-s muted">{{ tr('May not have: ', 'না-ও থাকতে পারে: ') + p.missing.join(', ') }}</span>
    <IconLine icon="payments">{{ pharmEstimateText(p.estimate) }}</IconLine>
    <TextArea v-model="address" :label="tr('Delivery address', 'কোথায় পৌঁছাবে (ঠিকানা)')" rows="2" maxlength="200" />
    <TextInput v-model="phone" :label="tr('Mobile number', 'মোবাইল নম্বর')" type="tel" inputmode="tel" maxlength="20" />
    <TextInput v-model="note" :label="tr('Note (optional)', 'নোট (ঐচ্ছিক)')" maxlength="200" />
    <span class="t-body-s muted">{{ tr('Pay the shop when it arrives (cash on delivery). The shop sees your address and number only for this order.', 'ওষুধ পৌঁছালে দোকানকে দাম দেবেন (ক্যাশ অন ডেলিভারি)। ঠিকানা আর নম্বর দোকান শুধু এই অর্ডারের জন্য দেখবে।') }}</span>
    <SendError :text="err" />
    <template #actions>
      <Button variant="text" :disabled="busy" @click="emit('close')">{{ tr('Cancel', 'বাতিল') }}</Button>
      <Button :busy="busy" :disabled="!address.trim() || !phone.trim()" @click="order">{{ tr('Place order', 'অর্ডার দিন') }}</Button>
    </template>
  </Dialog>
</template>
