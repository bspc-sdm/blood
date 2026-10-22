<script setup lang="ts">
// অ্যাকাউন্ট সাময়িক বন্ধ (profile_get-এর account.state = "deactivated", বা যেকোনো কাজে 423 `account_deactivated`):
// পুরো ওয়েবের বদলে এই পর্দা — "আবার চালু করুন" (account_reactivate, তারপর সব নতুন করে) আর "লগআউট"।
// নতুন লেখা, তাই বাংলা লাইনে bn-ok।
import { computed, ref } from 'vue';
import { Button, ErrorText, Icon, toast } from '@/ui/kit';
import { errorText } from '@/lib/api';
import { formatDayOf } from '@/lib/format';
import { trPlain } from '@/lib/i18n';
import { useSession } from '@/lib/session';
import { usePageTitle } from './chrome';

const s = useSession();
usePageTitle(() => trPlain('Account paused', 'অ্যাকাউন্ট সাময়িক বন্ধ')); // bn-ok
const busy = ref(false);
const error = ref<string | null>(null);
const since = computed(() => (s.account?.deactivatedAt ? formatDayOf(s.account.deactivatedAt) : null));

async function reactivate() {
  if (busy.value) return;
  busy.value = true;
  error.value = null;
  try {
    await s.reactivate();
    toast(trPlain('Your account is back on.', 'অ্যাকাউন্ট আবার চালু হয়েছে।')); // bn-ok
  } catch (e) {
    error.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
async function logout() {
  await s.signOut();
}
</script>

<template>
  <main class="paused" data-testid="account-paused">
    <div class="paused-card card col gap-12" role="alertdialog" aria-modal="true" aria-labelledby="paused-title">
      <span class="paused-mark"><Icon name="schedule" :size="32" /></span>
      <h1 id="paused-title" class="t-headline">{{ trPlain('Your account is paused', 'আপনার অ্যাকাউন্ট সাময়িক বন্ধ আছে') }}<!-- bn-ok --></h1>
      <p v-if="since" class="t-body-m muted">{{ trPlain(`Paused since ${since}.`, `${since} থেকে বন্ধ।`) }}<!-- bn-ok --></p>
      <p class="t-body-m">{{ trPlain('Your profile and listings are hidden and notifications are off. Nothing was deleted — turn it back on and everything is as before.', 'আপনার প্রোফাইল আর তালিকা লুকানো, নোটিফিকেশন বন্ধ। কিছুই মোছা হয়নি — চালু করলেই সব আগের মতো।') }}<!-- bn-ok --></p>
      <p v-if="s.email" class="t-body-s muted">{{ s.email }}</p>
      <ErrorText :text="error" />
      <div class="paused-actions">
        <Button icon="refresh" :busy="busy" @click="reactivate">{{ trPlain('Turn back on', 'আবার চালু করুন') }}<!-- bn-ok --></Button>
        <Button variant="text" icon="logout" :disabled="busy" @click="logout">{{ trPlain('Log out', 'লগআউট') }}</Button>
      </div>
    </div>
  </main>
</template>

<style scoped>
.paused { min-height: 100dvh; display: flex; align-items: center; justify-content: center; padding: 24px 16px; background: var(--bg); }
.paused-card { width: 100%; max-width: 460px; text-align: center; align-items: center; padding: 28px 20px; }
.paused-mark { display: inline-flex; width: 64px; height: 64px; border-radius: 50%; align-items: center; justify-content: center; background: var(--secondary-container); color: var(--on-secondary-container); }
.paused-actions { display: flex; flex-wrap: wrap; gap: 8px; justify-content: center; margin-top: 4px; }
</style>
