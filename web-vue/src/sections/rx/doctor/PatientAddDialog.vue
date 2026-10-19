<script setup lang="ts">
// নতুন রোগী
import { computed, ref } from 'vue';
import { Button, ChoiceChips, Dialog, ErrorText, TextInput } from '@/ui/kit';
import { call, errorText } from '@/lib/api';
import { toAsciiDigits, tr } from '@/lib/i18n';
import { forDoctor, genderLabel, type Actor, type RxPatient } from './model';

const props = defineProps<{ actor: Actor }>();
const emit = defineEmits<{ close: []; added: [p: RxPatient, existed: boolean] }>();
const name = ref('');
const age = ref('');
const gender = ref('');
const phone = ref('');
const busy = ref(false);
const err = ref<string | null>(null);
const ok = computed(() => !!name.value.trim() && !!phone.value.trim());
async function save() {
  if (!ok.value || busy.value) return;
  busy.value = true;
  err.value = null;
  try {
    const a = toAsciiDigits(age.value.trim());
    const r = await call<{ patient: RxPatient; existed: boolean }>('rx_patient_add', {
      ...forDoctor(props.actor), name: name.value.trim(), age: a ? Number(a) : null, gender: gender.value, phone: toAsciiDigits(phone.value.trim()),
    });
    emit('added', r.patient, r.existed);
  } catch (e) {
    err.value = errorText(e);
  }
  busy.value = false;
}
</script>

<template>
  <Dialog size="small" :title="tr('New patient', 'নতুন রোগী')" @close="() => !busy && emit('close')">
    <form class="col gap-12" @submit.prevent="save">
      <TextInput :label="tr('Name', 'নাম')" :model-value="name" autocomplete="off" @update:model-value="(v) => (name = v.slice(0, 80))" />
      <div class="row top gap-12 rxd-age-row">
        <TextInput :label="tr('Age (years)', 'বয়স (বছর)')" inputmode="numeric" :model-value="age" @update:model-value="(v) => (age = v.replace(/[^0-9০-৯]/g, '').slice(0, 3))" />
        <div class="field">
          <span class="label">{{ tr('Gender', 'লিঙ্গ') }}</span>
          <ChoiceChips
            :options="[{ value: 'male', label: genderLabel('male') }, { value: 'female', label: genderLabel('female') }]"
            :model-value="gender"
            @update:model-value="(v: string) => (gender = gender === v ? '' : v)"
          />
        </div>
      </div>
      <TextInput :label="tr('Mobile number', 'মোবাইল নম্বর')" type="tel" inputmode="tel" :model-value="phone" autocomplete="off" @update:model-value="(v) => (phone = v.slice(0, 20))" />
      <p class="t-body-s muted">{{ tr('The patient adds the prescription to their own app with this number and the code.', 'রোগী এই নম্বর আর কোড দিয়ে নিজের অ্যাপে প্রেসক্রিপশন যোগ করবেন।') }}</p>
      <ErrorText :text="err" />
      <button type="submit" hidden />
    </form>
    <template #actions>
      <Button variant="text" :disabled="busy" @click="emit('close')">{{ tr('Cancel', 'বাতিল') }}</Button>
      <Button :busy="busy" :disabled="!ok" @click="save">{{ tr('Save', 'সেভ') }}</Button>
    </template>
  </Dialog>
</template>
