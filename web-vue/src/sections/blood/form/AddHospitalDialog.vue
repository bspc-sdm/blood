<script setup lang="ts">
// হাসপাতাল যোগের অনুরোধ (অ্যাপের AddHospitalDialog; জেলা = রিকোয়েস্টের এলাকা)
import { computed, ref } from 'vue';
import { Button, ChoiceChips, Dialog, ErrorText, Field, Icon, TextInput } from '@/ui/kit';
import { call, errorText } from '@/lib/api';
import { toAsciiDigits, tr } from '@/lib/i18n';
import { districtLabel } from '@/lib/geo';
import { nameCase } from '../model';
import { HOSPITAL_TYPES, typeLabel, type Hospital } from './hospital';

const props = defineProps<{ initialName: string; district: string | null }>();
const emit = defineEmits<{ existing: [h: Hospital]; sent: [m: string]; dismiss: [] }>();
const latin = /[A-Za-z]/.test(props.initialName);
const nameEn = ref(latin ? props.initialName : '');
const nameBn = ref(latin ? '' : props.initialName);
const type = ref('private_hospital');
const address = ref('');
const phone = ref('');
const busy = ref(false);
const err = ref<string | null>(null);
const typeOpts = computed(() => HOSPITAL_TYPES.map((t) => ({ value: t, label: typeLabel(t) })));
async function send() {
  if (!props.district) return (err.value = tr('Choose a district', 'জেলা বেছে নিন'));
  if (!/[A-Za-z]/.test(nameEn.value)) return (err.value = tr('Enter the name in English', 'ইংরেজিতে নাম লিখুন'));
  busy.value = true;
  err.value = null;
  try {
    const r = await call<{ ok: boolean; merged?: boolean; duplicateOf?: Hospital }>('hospital_request', {
      nameEn: nameCase(nameEn.value), nameBn: nameBn.value.trim(), type: type.value, district: props.district, upazilaId: null, unionId: null, address: address.value.trim(), phone: phone.value,
    });
    if (r.duplicateOf) emit('existing', r.duplicateOf);
    else if (r.merged) emit('sent', tr('Someone already asked to add this hospital — your request was added to theirs.', 'এই হাসপাতালের অনুরোধ আগেই এসেছে — আপনারটাও তার সাথে যোগ হলো।'));
    else emit('sent', tr('Request sent. You can choose it after an admin approves.', 'অনুরোধ পাঠানো হয়েছে। অ্যাডমিন অনুমোদন দিলে বেছে নিতে পারবেন।'));
  } catch (e) {
    err.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <Dialog :title="tr('Add a hospital', 'হাসপাতাল যোগের অনুরোধ')" @close="!busy && emit('dismiss')">
    <p class="t-body-s muted">{{ tr('An admin checks it before it appears in the list.', 'অ্যাডমিন যাচাই করার পর তালিকায় আসবে।') }}</p>
    <div v-if="district" class="row gap-6 t-body-m"><Icon name="location_on" :size="18" class="c-primary" />{{ districtLabel(district) }}</div>
    <TextInput v-model="nameEn" :label="tr('Name in English', 'ইংরেজিতে নাম')" maxlength="120" />
    <TextInput v-model="nameBn" :label="tr('Name in Bangla (optional)', 'বাংলায় নাম (ঐচ্ছিক)')" maxlength="120" />
    <Field :label="tr('Type', 'ধরন')">
      <ChoiceChips v-model="type" :options="typeOpts" />
    </Field>
    <TextInput v-model="address" :label="tr('Address (optional)', 'ঠিকানা (ঐচ্ছিক)')" maxlength="200" />
    <TextInput
      :label="tr('Phone (optional)', 'ফোন (ঐচ্ছিক)')"
      inputmode="tel"
      :model-value="phone"
      @update:model-value="(v: string) => (phone = toAsciiDigits(v).replace(/[^0-9+]/g, '').slice(0, 15))"
    />
    <ErrorText :text="err" />
    <template #actions>
      <Button variant="text" :disabled="busy" @click="emit('dismiss')">{{ tr('Cancel', 'বাতিল') }}</Button>
      <Button :busy="busy" @click="send">{{ tr('Send', 'পাঠান') }}</Button>
    </template>
  </Dialog>
</template>
