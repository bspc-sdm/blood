<script setup lang="ts">
// পরিবারের সদস্য যোগ/বদল
import { ref } from 'vue';
import { Button, ChoiceChips, Dialog, ErrorText, TextInput } from '@/ui/kit';
import { toAsciiDigits, tr } from '@/lib/i18n';
import { normalizePhone } from '@/lib/format';
import type { FamilyMember } from './care';

const props = defineProps<{ existing: FamilyMember | null; busy: boolean; onDelete?: () => void }>();
const emit = defineEmits<{ close: []; save: [m: FamilyMember] }>();
const ex = props.existing;
const name = ref(ex?.name ?? '');
const relation = ref(ex?.relation ?? '');
const age = ref(ex?.age != null ? String(ex.age) : '');
const gender = ref(ex?.gender ?? '');
const phone = ref(ex?.phone ?? '');
const error = ref<string | null>(null);

function save() {
  const a = parseInt(toAsciiDigits(age.value), 10);
  const p = phone.value.trim();
  if (p && !normalizePhone(p)) {
    error.value = tr('Enter a valid mobile number (01…, 11 digits)', 'সঠিক মোবাইল নম্বর দিন (01 দিয়ে শুরু, ১১ সংখ্যা)');
    return;
  }
  error.value = null;
  emit('save', {
    id: ex?.id ?? '',
    name: name.value.trim(),
    relation: relation.value.trim(),
    age: Number.isFinite(a) && a >= 0 && a <= 120 ? a : null,
    gender: gender.value,
    phone: p ? normalizePhone(p)! : '',
  });
}
</script>

<template>
  <Dialog :title="existing ? existing.name : tr('Family member', 'পরিবারের সদস্য')" @close="() => !busy && emit('close')">
    <TextInput v-model="name" :label="tr('Name', 'নাম')" maxlength="60" />
    <TextInput v-model="relation" :label="tr('Relation (e.g. Mother)', 'সম্পর্ক (যেমন: মা)')" maxlength="30" />
    <TextInput :label="tr('Age', 'বয়স')" inputmode="numeric" :model-value="age" @update:model-value="(v) => (age = v.replace(/[^0-9০-৯]/g, '').slice(0, 3))" />
    <ChoiceChips
      :options="[{ value: 'male', label: tr('Male', 'পুরুষ') }, { value: 'female', label: tr('Female', 'নারী') }]"
      :model-value="gender"
      @update:model-value="(g: string) => (gender = gender === g ? '' : g)"
    />
    <TextInput
      v-model="phone"
      :label="tr('Mobile number (optional)', 'মোবাইল নম্বর (ঐচ্ছিক)')"
      inputmode="tel"
      maxlength="20"
      :hint="tr('The number given at the chamber — used to add their prescriptions.', 'চেম্বারে যে নম্বর দেওয়া হয় — এটা দিয়ে তাঁর প্রেসক্রিপশন যোগ হবে।')"
    />
    <ErrorText :text="error" />
    <template #actions>
      <Button v-if="onDelete" variant="danger-text" :disabled="busy" class="acct-left-act" @click="onDelete()">{{ tr('Remove this member', 'এই সদস্যকে সরান') }}</Button>
      <Button variant="text" :disabled="busy" @click="emit('close')">{{ tr('Cancel', 'বাতিল') }}</Button>
      <Button :busy="busy" :disabled="!name.trim()" @click="save">{{ tr('Save', 'সেভ') }}</Button>
    </template>
  </Dialog>
</template>
