<script setup lang="ts">
// কাগজের প্রেসক্রিপশনের কোড + চেম্বারে দেওয়া ফোন নম্বর (অ্যাপের RxClaimDialog)
import { ref } from 'vue';
import { Button, Dialog, ErrorText, TextInput } from '@/ui/kit';
import { call, errorText } from '@/lib/api';
import { tr } from '@/lib/i18n';
import { useSession } from '@/lib/session';

const emit = defineEmits<{ close: []; added: [n: number] }>();
const s = useSession();
const code = ref('');
const phone = ref(s.profile?.phone ?? '');
const busy = ref(false);
const err = ref<string | null>(null);
async function add() {
  busy.value = true;
  err.value = null;
  try {
    const r = await call<{ added?: number }>('rx_claim', { code: code.value, phone: phone.value.trim() });
    emit('added', r.added ?? 0);
  } catch (e) {
    err.value = errorText(e);
    busy.value = false;
  }
}
</script>

<template>
  <Dialog :title="tr('Add a prescription', 'প্রেসক্রিপশন যোগ করুন')" @close="!busy && emit('close')">
    <p class="t-body-m">
      {{ tr(
        'Write the code printed under the QR, and the mobile number given at the chamber — yours, or a family member\'s (add them in Profile → Caregivers & family).',
        'QR-এর নিচে লেখা কোড আর চেম্বারে যে মোবাইল নম্বর দেওয়া হয়েছিল — আপনার, অথবা পরিবারের সদস্যের (প্রোফাইল → কেয়ারগিভার ও পরিবার-এ যোগ করে রাখুন)।',
      ) }}
    </p>
    <TextInput
      :label="tr('Code', 'কোড')"
      :model-value="code"
      autocomplete="off"
      autocapitalize="characters"
      :spellcheck="false"
      class="input health-code"
      @update:model-value="(v: string) => (code = v.toUpperCase().replace(/[^A-Z0-9]/g, '').slice(0, 10))"
    />
    <TextInput v-model="phone" :label="tr('Mobile number', 'মোবাইল নম্বর')" type="tel" inputmode="tel" maxlength="20" />
    <ErrorText :text="err" />
    <template #actions>
      <Button variant="text" :disabled="busy" @click="emit('close')">{{ tr('Cancel', 'বাতিল') }}</Button>
      <Button :busy="busy" :disabled="code.length !== 10 || !phone.trim()" @click="add">{{ tr('Add', 'যোগ করুন') }}</Button>
    </template>
  </Dialog>
</template>
