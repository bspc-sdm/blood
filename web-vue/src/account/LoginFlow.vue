<script setup lang="ts">
// লগইন-গেট (অ্যাপের LoginGate + LoginScreen): ইমেইল → কোড → (দরকার হলে) নাম-মোবাইল → (দরকার হলে) রক্তদাতার তথ্য,
// তারপর যে কাজের জন্য লগইন চাওয়া হয়েছিল সেটাই চলে।
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { Button, Dialog, ErrorText, InfoText, TextInput, toast } from '@/ui/kit';
import { errorText } from '@/lib/api';
import { num, toAsciiDigits, tr, trPlain } from '@/lib/i18n';
import { useSession } from '@/lib/session';
import { isComplete, isDonor, saveContact, saveDonor, type Profile } from '@/lib/profile';
import ProfileForm from './ProfileForm.vue';
import DemoRoleList from '@/demo/DemoRoleList.vue'; // ডেমো

type Step = 'email' | 'code' | 'contact' | 'donor';

const s = useSession();
const p = s.pending!;
const step = ref<Step>(s.guest ? 'email' : 'contact');
const email = ref('');
const code = ref('');
const busy = ref(false);
const error = ref<string | null>(null);
const info = ref<string | null>(null);
const wait = ref(0);
let waitTimer: ReturnType<typeof setTimeout> | undefined;

watch(wait, (w) => {
  clearTimeout(waitTimer);
  if (w <= 0) return;
  waitTimer = setTimeout(() => (wait.value = w - 1), 1000);
});
onBeforeUnmount(() => clearTimeout(waitTimer));

// লগইন হয়ে গেলে/প্রোফাইল এলে পরের ধাপ ঠিক করা
watch(
  () => [s.guest, s.profileLoaded, s.profile, step.value] as const,
  () => {
    if (s.guest || !s.profileLoaded) return;
    if (step.value === 'email' || step.value === 'code') return;
    if (!isComplete(s.profile)) step.value = 'contact';
    else if (p.donor && !isDonor(s.profile)) step.value = 'donor';
    else s.finishPending(true);
  },
  { immediate: true },
);

const cleanEmail = computed(() => email.value.trim().toLowerCase());
const validEmail = computed(() => /^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/.test(cleanEmail.value));

async function sendCode() {
  if (!validEmail.value) return (error.value = tr('Enter a valid email', 'সঠিক ইমেইল দিন'));
  busy.value = true;
  error.value = null;
  try {
    await s.sendCode(cleanEmail.value);
    step.value = 'code';
    info.value = trPlain('We sent a 6-digit code. Check Spam too.', '৬ সংখ্যার কোড পাঠানো হয়েছে। Spam ফোল্ডারও দেখুন।');
    wait.value = 60;
  } catch (e) {
    error.value = errorText(e);
  } finally {
    busy.value = false;
  }
}

async function verify() {
  const c = toAsciiDigits(code.value).replace(/\D/g, '');
  if (c.length !== 6) return (error.value = tr('Enter the 6-digit code', '৬ সংখ্যার কোড দিন'));
  busy.value = true;
  error.value = null;
  try {
    const r = await s.signIn(cleanEmail.value, c);
    if (r.restored) toast(tr('Account deletion cancelled. Everything is back.', 'অ্যাকাউন্ট মোছা বাতিল হয়েছে। সব আগের মতো আছে।'));
    step.value = 'contact';
  } catch (e) {
    error.value = errorText(e);
  } finally {
    busy.value = false;
  }
}

async function saveProfile(next: Profile, includeDonation: boolean, kind: 'contact' | 'donor') {
  if (!s.uid) return;
  busy.value = true;
  error.value = null;
  try {
    if (kind === 'contact') await saveContact(s.uid, next.name, next.phone);
    else await saveDonor(s.uid, next, includeDonation);
    await s.reloadProfile();
  } catch (e) {
    error.value = errorText(e) || tr("Couldn't save. Check your internet.", 'সেভ হয়নি, ইন্টারনেট দেখুন।');
  } finally {
    busy.value = false;
  }
}

const title = computed(() =>
  step.value === 'email' || step.value === 'code' ? tr('Log in', 'লগইন') : step.value === 'contact' ? tr('Your profile', 'আপনার প্রোফাইল') : tr('Donor details', 'রক্তদাতার তথ্য'),
);
function changeEmail() {
  step.value = 'email';
  code.value = '';
  error.value = null;
}
</script>

<template>
  <Dialog :title="title" @close="s.finishPending(false)">
    <!-- লগইনের কারণ (যেমন "রক্তদাতা হতে লগইন করুন") শুধু লগইনের ধাপে — লগইন থাকা অবস্থায় প্রোফাইল/রক্তদাতার তথ্যে নয় -->
    <p v-if="p.reason && (step === 'email' || step === 'code')" class="t-body-m" :style="{ background: 'var(--secondary-container)', color: 'var(--on-secondary-container)', padding: '10px 14px', borderRadius: '12px' }">{{ p.reason }}</p>
    <form v-if="step === 'email'" class="col gap-12" @submit.prevent="sendCode">
      <p class="t-body-m muted">{{ tr('We will email you a code. No password needed.', 'ইমেইলে একটা কোড যাবে, পাসওয়ার্ড লাগবে না।') }}</p>
      <TextInput v-model="email" :label="tr('Email', 'ইমেইল')" type="email" inputmode="email" autocomplete="email" autofocus />
      <ErrorText :text="error" />
      <div class="form-actions">
        <Button type="submit" :busy="busy">{{ tr('Send code', 'কোড পাঠান') }}</Button>
      </div>
    </form>
    <DemoRoleList v-if="step === 'email'" @done="step = 'contact'" /><!-- ডেমো -->
    <form v-if="step === 'code'" class="col gap-12" @submit.prevent="verify">
      <InfoText :text="info" />
      <p class="t-body-m">{{ cleanEmail }} <button type="button" class="btn text small" @click="changeEmail">{{ tr('Change', 'বদলান') }}</button></p>
      <TextInput
        :label="tr('6-digit code', '৬ সংখ্যার কোড')"
        inputmode="numeric"
        autocomplete="one-time-code"
        autofocus
        maxlength="6"
        :model-value="code"
        @update:model-value="(v: string) => (code = v.replace(/[^0-9০-৯]/g, '').slice(0, 6))"
      />
      <ErrorText :text="error" />
      <div class="form-actions">
        <Button variant="text" :disabled="wait > 0 || busy" @click="sendCode">
          {{ wait > 0 ? tr(`Resend in ${wait}s`, `${num(wait)} সেকেন্ড পর আবার পাঠান`) : tr('Resend code', 'আবার কোড পাঠান') }}
        </Button>
        <Button type="submit" :busy="busy">{{ tr('Log in', 'লগইন') }}</Button>
      </div>
    </form>
    <ProfileForm v-if="step === 'contact' && s.profileLoaded" :existing="s.profile" mode="basic" :busy="busy" :error="error" :submit-label="tr('Continue', 'এগিয়ে যান')" @save="(np, inc) => saveProfile(np, inc, 'contact')" />
    <ProfileForm v-if="step === 'donor' && s.profileLoaded" :existing="s.profile" mode="donor" :busy="busy" :error="error" :submit-label="tr('Continue', 'এগিয়ে যান')" @save="(np, inc) => saveProfile(np, inc, 'donor')" />
    <div v-if="(step === 'contact' || step === 'donor') && !s.profileLoaded" class="loading"><span class="spinner" /></div>
  </Dialog>
</template>
