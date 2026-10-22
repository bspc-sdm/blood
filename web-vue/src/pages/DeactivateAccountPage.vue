<script setup lang="ts">
// /deactivate-account — অ্যাকাউন্ট সাময়িক বন্ধ (account_deactivate): মোছার পাতার "মুছে না ফেলে সাময়িক বন্ধ রাখুন",
// প্রোফাইল আর সেটিংস থেকে আসে। কী হয় (লুকানো, নোটিফিকেশন নেই, কিছু মোছে না, সময়ের সীমা নেই, লগইন করে আবার চালু),
// টিক ছাড়াই এক চাপে "সাময়িক বন্ধ করুন", তারপর লগআউট। লগইন না থাকলে "লগইন করে বন্ধ করুন"।
// নতুন লেখা, তাই বাংলা লাইনে bn-ok।
import { ref } from 'vue';
import { Button, ErrorText, Icon, Loading } from '@/ui/kit';
import { usePageTitle } from '@/shell/chrome';
import { call, errorText } from '@/lib/api';
import { trPlain } from '@/lib/i18n';
import { useSession } from '@/lib/session';
import { useNav } from '@/lib/nav';

const s = useSession();
const nav = useNav();
usePageTitle(() => trPlain('Pause account', 'অ্যাকাউন্ট সাময়িক বন্ধ')); // bn-ok

const busy = ref(false);
const error = ref<string | null>(null);
const done = ref(false);

async function deactivate() {
  if (busy.value) return;
  busy.value = true;
  error.value = null;
  try {
    await call('account_deactivate');
    await s.signOut();
    done.value = true;
    window.scrollTo(0, 0);
  } catch (e) {
    error.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <div class="acct-page">
    <div class="acct-narrow col gap-12 pause-page">
      <header class="col gap-4">
        <h1 class="t-headline">{{ trPlain('Pause your account', 'অ্যাকাউন্ট সাময়িক বন্ধ রাখুন') }}<!-- bn-ok --></h1>
        <p class="t-body-m muted">{{ trPlain('Need a break? Pause your account instead of deleting it.', 'কিছুদিনের বিরতি চাইলে অ্যাকাউন্ট না মুছে সাময়িক বন্ধ রাখুন।') }}<!-- bn-ok --></p>
      </header>

      <section v-if="done" class="card pause-done col gap-8" role="status" data-testid="pause-done">
        <div class="row gap-8"><Icon name="check_circle" :size="24" /><h2 class="t-title-m bold">{{ trPlain('Your account is paused', 'অ্যাকাউন্ট সাময়িক বন্ধ হয়েছে') }}<!-- bn-ok --></h2></div>
        <p class="t-body-m">{{ trPlain('You have been logged out. Nothing was deleted. Log in any time to turn it back on.', 'লগআউট হয়েছে। কিছুই মোছা হয়নি। আবার চালু করতে যেকোনো সময় লগইন করুন।') }}<!-- bn-ok --></p>
        <div><Button variant="tonal" icon="nav_home" @click="nav('/', { replace: true })">{{ trPlain('Go to home', 'হোমে যান') }}<!-- bn-ok --></Button></div>
      </section>

      <template v-else>
        <section class="card col gap-8">
          <h2 class="t-title-m bold">{{ trPlain('While it is paused', 'বন্ধ থাকলে') }}<!-- bn-ok --></h2>
          <ul class="pause-list t-body-m" data-testid="pause-what">
            <li>{{ trPlain('Nobody sees your profile, and you are not shown as a donor.', 'আপনার প্রোফাইল কেউ দেখবে না, রক্তদাতার তালিকাতেও থাকবেন না।') }}<!-- bn-ok --></li>
            <li>{{ trPlain('Your shop, lab, ambulance, blood bank and chamber listings are hidden.', 'আপনার দোকান, ল্যাব, অ্যাম্বুলেন্স, ব্লাড ব্যাংক আর চেম্বারের তালিকা লুকানো থাকবে।') }}<!-- bn-ok --></li>
            <li>{{ trPlain('You get no notifications.', 'কোনো নোটিফিকেশন আসবে না।') }}<!-- bn-ok --></li>
            <li>{{ trPlain('Nothing is deleted — all your data stays as it is.', 'কিছুই মুছবে না — সব তথ্য যেমন আছে তেমনই থাকবে।') }}<!-- bn-ok --></li>
            <li>{{ trPlain('There is no time limit — keep it paused as long as you like.', 'সময়ের কোনো সীমা নেই — যতদিন খুশি বন্ধ রাখুন।') }}<!-- bn-ok --></li>
            <li>{{ trPlain('To turn it back on, just log in again and tap "Turn back on".', 'আবার চালু করতে শুধু লগইন করে "আবার চালু করুন" চাপুন।') }}<!-- bn-ok --></li>
          </ul>
        </section>

        <section class="card col gap-8">
          <Loading v-if="!s.ready" />
          <template v-else-if="s.guest">
            <p class="t-body-m">{{ trPlain('Log in with your email (we send a 6-digit code), then pause.', 'ইমেইল দিয়ে লগইন করুন (৬ সংখ্যার কোড যাবে), তারপর বন্ধ করুন।') }}<!-- bn-ok --></p>
            <div><Button icon="schedule" @click="s.login()">{{ trPlain('Log in and pause', 'লগইন করে বন্ধ করুন') }}<!-- bn-ok --></Button></div>
          </template>
          <template v-else>
            <p class="t-body-m">{{ trPlain('Logged in as', 'লগইন করা আছে:') }}<!-- bn-ok --> <strong>{{ s.email }}</strong></p>
            <p class="t-body-s muted">{{ trPlain('You will be logged out after pausing.', 'বন্ধ করলে লগআউট হয়ে যাবে।') }}<!-- bn-ok --></p>
            <ErrorText :text="error" />
            <div><Button icon="schedule" :busy="busy" data-testid="pause-confirm" @click="deactivate">{{ trPlain('Pause account', 'সাময়িক বন্ধ করুন') }}<!-- bn-ok --></Button></div>
          </template>
        </section>

        <p class="t-body-s muted">
          {{ trPlain('Want it gone for good instead?', 'চিরতরে মুছতে চান?') }}<!-- bn-ok -->
          <a href="/delete-account" @click.prevent="nav('/delete-account')">{{ trPlain('Delete account', 'অ্যাকাউন্ট মুছুন') }}</a>
        </p>
      </template>
    </div>
  </div>
</template>

<style scoped>
.pause-page { padding-top: 8px; }
.pause-page a { color: var(--primary); }
.pause-list { margin: 0; padding-left: 20px; list-style: disc; display: flex; flex-direction: column; gap: 6px; }
.pause-done { border: 1px solid var(--outline-variant, var(--outline)); }
</style>
