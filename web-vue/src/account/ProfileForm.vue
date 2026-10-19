<script setup lang="ts">
// প্রোফাইলের ফর্ম (অ্যাপের ProfileScreen): BASIC = নাম-মোবাইল, DONOR = রক্তদাতার তথ্য, FULL = সব
import { computed, ref } from 'vue';
import { Button, Check, ErrorText, TextInput } from '@/ui/kit';
import { formatDate, fromDateInput, dateInputValue, normalizePhone, todayUtcMidnight } from '@/lib/format';
import { toAsciiDigits, tr } from '@/lib/i18n';
import { UNKNOWN_GROUP, ageYears } from '@/lib/rules';
import type { Profile } from '@/lib/profile';
import { nameCase, type ProfileMode } from './nameCase';
import BloodGroupChips from './BloodGroupChips.vue';

const props = defineProps<{ existing: Profile | null; mode: ProfileMode; busy?: boolean; error?: string | null; submitLabel?: string }>();
const emit = defineEmits<{ save: [p: Profile, includeDonation: boolean] }>();
const existing = props.existing;
const name = ref(existing?.name ?? '');
const phone = ref(existing?.phone ?? '');
const group = ref(props.mode === 'donor' && existing?.bloodGroup === UNKNOWN_GROUP ? '' : existing?.bloodGroup ?? '');
const birth = ref<number | null>(existing?.birthDateMillis ?? null);
const weight = ref(existing?.weightKg != null ? String(existing.weightKg) : '');
const saved = existing?.lastDonationMillis ?? null;
const never = ref(!!existing && !!existing.bloodGroup && existing.lastDonationMillis == null);
const last = ref<number | null>(saved);
const formError = ref<string | null>(null);
const showContact = computed(() => props.mode !== 'donor');
const showDonor = computed(() => props.mode !== 'basic');
const today = dateInputValue(todayUtcMidnight());

function submit() {
  const cleanPhone = normalizePhone(phone.value);
  const age = ageYears(birth.value) ?? 0;
  const w = parseInt(toAsciiDigits(weight.value), 10);
  const mode = props.mode;
  const donorTouched = mode === 'donor' || (mode === 'full' && (!!group.value || birth.value != null || !!weight.value.trim() || last.value != null || saved != null));
  let err: string | null = null;
  if (showContact.value && !name.value.trim()) err = tr('Enter your name', 'নাম লিখুন');
  else if (showContact.value && !cleanPhone) err = tr('Enter a valid mobile number (01…, 11 digits)', 'সঠিক মোবাইল নম্বর দিন (01 দিয়ে শুরু, ১১ সংখ্যা)');
  else if (donorTouched) {
    if (!group.value) err = tr('Choose your blood group', 'রক্তের গ্রুপ বেছে নিন');
    else if (birth.value == null) err = tr('Add your birth date', 'জন্ম তারিখ দিন');
    else if (age < 10 || age > 100) err = tr('Check your birth date', 'জন্ম তারিখ ঠিক নেই');
    else if (!(w >= 20 && w <= 250)) err = tr('Enter a valid weight (kg)', 'সঠিক ওজন দিন (কেজি)');
    else if (saved == null && !never.value && last.value == null) err = tr('Add your last donation date, or tick "never donated"', 'শেষ রক্তদানের তারিখ দিন, অথবা "কখনো রক্ত দিইনি" টিক দিন');
    else if ((last.value ?? 0) > todayUtcMidnight()) err = tr("Date can't be in the future", 'ভবিষ্যতের তারিখ দেওয়া যাবে না');
  }
  formError.value = err;
  if (err) return;
  const base: Profile = existing ?? ({} as Profile);
  const out: Profile = {
    ...base,
    ...(showContact.value && cleanPhone ? { name: nameCase(name.value), phone: cleanPhone } : {}),
    ...(donorTouched ? { bloodGroup: group.value, birthDateMillis: birth.value, weightKg: w, lastDonationMillis: saved ?? (never.value ? null : last.value) } : {}),
    notify: existing?.notify ?? true,
  };
  emit('save', out, donorTouched && saved == null);
}
function onNever(v: boolean) {
  never.value = v;
  if (v) last.value = null;
}
</script>

<template>
  <form class="col gap-16" novalidate @submit.prevent="submit">
    <p v-if="mode === 'donor'" class="t-body-m muted">
      {{ tr(
        'We use these to know when you can donate and to send you only requests your blood group can help.',
        'আপনি কখন রক্ত দিতে পারবেন আর কোন রিকোয়েস্ট আপনার গ্রুপের সাথে মেলে, তা বুঝতে এগুলো লাগে।',
      ) }}
    </p>
    <template v-if="showContact">
      <TextInput v-model="name" :label="tr('Name', 'নাম')" maxlength="80" autocomplete="name" />
      <TextInput
        v-model="phone"
        :label="tr('Mobile number', 'মোবাইল নম্বর')"
        inputmode="tel"
        autocomplete="tel"
        placeholder="01XXXXXXXXX"
        :hint="tr('Shown only after you agree to donate', 'শুধু &quot;রক্ত দেব&quot; চাপার পর অন্যজন দেখবেন')"
      />
    </template>
    <template v-if="showDonor">
      <h3 v-if="mode === 'full'" class="t-title-m bold">{{ tr('Donor details (only if you want to donate blood)', 'রক্তদাতার তথ্য (শুধু রক্ত দিতে চাইলে)') }}</h3>
      <div class="col gap-6">
        <span class="t-title-s">{{ tr('Blood group', 'রক্তের গ্রুপ') }}</span>
        <BloodGroupChips v-model="group" :allow-unknown="mode === 'full'" />
      </div>
      <div class="row top gap-12" :style="{ flexWrap: 'wrap' }">
        <div class="grow" :style="{ minWidth: '180px' }">
          <TextInput type="date" :label="tr('Birth date', 'জন্ম তারিখ')" :max="today" :model-value="dateInputValue(birth)" @update:model-value="(v: string) => (birth = fromDateInput(v))" />
        </div>
        <div class="grow" :style="{ minWidth: '140px' }">
          <TextInput :label="tr('Weight (kg)', 'ওজন (কেজি)')" inputmode="numeric" :model-value="weight" @update:model-value="(v: string) => (weight = v.replace(/[^0-9০-৯]/g, '').slice(0, 3))" />
        </div>
      </div>
      <div class="col gap-6">
        <span class="t-title-s">{{ tr('Last donation', 'শেষ রক্তদান') }}</span>
        <template v-if="saved != null">
          <span class="t-body-l">{{ formatDate(saved) }}</span>
          <span class="t-body-s muted">{{ tr('To change it, use "Change last donation date" in Profile.', 'বদলাতে প্রোফাইলের "শেষ রক্তদানের তারিখ বদলান" চাপুন।') }}</span>
        </template>
        <template v-else>
          <Check :model-value="never" @update:model-value="onNever">{{ tr('I have never donated', 'কখনো রক্ত দিইনি') }}</Check>
          <TextInput v-if="!never" type="date" :aria-label="tr('Last donation', 'শেষ রক্তদান')" :max="today" :model-value="dateInputValue(last)" @update:model-value="(v: string) => (last = fromDateInput(v))" />
        </template>
      </div>
    </template>
    <ErrorText :text="formError ?? error" />
    <div class="form-actions">
      <Button type="submit" :busy="busy">{{ submitLabel ?? tr('Save', 'সেভ করুন') }}</Button>
    </div>
  </form>
</template>
