<script setup lang="ts">
// /delete-account — পাবলিক পাতা (Google Play-র "Delete account URL", app/PLAY-STORE.md §৮): লগইন ছাড়াই খোলে।
// কী মোছে, কবে মোছে; লগইন থাকলে এখান থেকেই মোছা (প্রোফাইলের একই ডায়ালগ), না থাকলে "লগইন করে মুছুন" আর ইমেইলের পথ।
// লেখার সারাংশ PRIVACY.md-এর "অ্যাকাউন্ট মোছা" থেকে — ওটা বদলালে এখানেও মেলান। নতুন লেখা, তাই বাংলা লাইনে bn-ok।
import { computed, ref, watch } from 'vue';
import { Button, Icon, Loading } from '@/ui/kit';
import { usePageTitle } from '@/shell/chrome';
import { formatDate } from '@/lib/format';
import { trPlain } from '@/lib/i18n';
import { useSession } from '@/lib/session';
import { useSearchParams } from '@/lib/nav';
import DeleteAccountDialog from '@/sections/account/DeleteAccountDialog.vue';
import { SUPPORT_EMAIL } from '@/sections/account/util';

const s = useSession();
const [params] = useSearchParams();
usePageTitle(() => trPlain('Delete account', 'অ্যাকাউন্ট মুছুন')); // bn-ok

const confirmOpen = ref(false);
const done = ref<{ deleteAt: number | null } | null>(null);
// "লগইন করে মুছুন" চাপলে লগইন শেষ হলেই (প্রোফাইলের ধাপ বাদ দিলেও) মোছার নিশ্চিতকরণ খোলে; ?confirm=1 দিয়েও
const wantDelete = ref(params.value.get('confirm') === '1');
watch(
  () => [s.ready, s.guest, s.pending] as const,
  ([ready, guest, pending]) => {
    if (ready && !guest && !pending && wantDelete.value && !done.value) {
      wantDelete.value = false;
      confirmOpen.value = true;
    }
  },
  { immediate: true },
);

function loginToDelete() {
  wantDelete.value = true;
  s.login();
}
function deleted(deleteAt: number | null) {
  confirmOpen.value = false;
  done.value = { deleteAt };
}

// PRIVACY.md-এর "অ্যাকাউন্ট মোছা" / "Deleting your account" অংশ (deploy/ci/render-legal.py-র শিরোনাম-id)
const privacyHref = computed(() => trPlain('/privacy#deleting-your-account', '/privacy#অ্যাকাউন্ট-মোছা')); // bn-ok
const mailto = computed(
  () => `mailto:${SUPPORT_EMAIL}?subject=${encodeURIComponent('Delete my Lifolo account / Lifolo অ্যাকাউন্ট মুছুন')}`, // bn-ok
);
</script>

<template>
  <div class="acct-page">
    <div class="acct-narrow col gap-12 del-page">
      <header class="col gap-4">
        <h1 class="t-headline">{{ trPlain('Delete your Lifolo account', 'Lifolo অ্যাকাউন্ট মুছুন') }}<!-- bn-ok --></h1>
        <p class="t-body-m muted">{{ trPlain('Lifolo — Android app and website (lifolo.com). Developer: Lifolo.', 'Lifolo — Android অ্যাপ আর ওয়েবসাইট (lifolo.com)। ডেভেলপার: Lifolo।') }}<!-- bn-ok --></p>
      </header>

      <!-- মোছার অনুরোধ হয়ে গেছে -->
      <section v-if="done" class="card del-done col gap-8" role="status" data-testid="delete-done">
        <div class="row gap-8"><Icon name="check_circle" :size="24" /><h2 class="t-title-m bold">{{ trPlain('Deletion requested', 'মোছার অনুরোধ নেওয়া হয়েছে') }}<!-- bn-ok --></h2></div>
        <p v-if="done.deleteAt" class="t-body-m">{{ trPlain(`Your account will be deleted on ${formatDate(done.deleteAt)}.`, `আপনার অ্যাকাউন্ট ${formatDate(done.deleteAt)} তারিখে মুছে যাবে।`) }}<!-- bn-ok --></p>
        <p class="t-body-m">{{ trPlain('You have been logged out on every device. Log in again before then if you change your mind — that cancels the deletion.', 'সব ফোন আর ব্রাউজার থেকে লগআউট হয়েছে। মত বদলালে এর আগে আবার লগইন করুন — তাতে মোছা বাতিল হবে।') }}<!-- bn-ok --></p>
      </section>

      <!-- এখানে মোছা -->
      <section v-else class="card col gap-8">
        <h2 class="t-title-m bold">{{ trPlain('Delete it here', 'এখানেই মুছুন') }}<!-- bn-ok --></h2>
        <Loading v-if="!s.ready" />
        <template v-else-if="s.guest">
          <p class="t-body-m">{{ trPlain('Log in with your email (we send a 6-digit code), then confirm.', 'ইমেইল দিয়ে লগইন করুন (৬ সংখ্যার কোড যাবে), তারপর নিশ্চিত করুন।') }}<!-- bn-ok --></p>
          <div><Button variant="danger" icon="delete" @click="loginToDelete">{{ trPlain('Log in and delete', 'লগইন করে মুছুন') }}<!-- bn-ok --></Button></div>
        </template>
        <template v-else>
          <p class="t-body-m">{{ trPlain('Logged in as', 'লগইন করা আছে:') }}<!-- bn-ok --> <strong>{{ s.email }}</strong></p>
          <div><Button variant="danger" icon="delete" @click="confirmOpen = true">{{ trPlain('Delete my account', 'আমার অ্যাকাউন্ট মুছুন') }}<!-- bn-ok --></Button></div>
        </template>
      </section>

      <section class="card col gap-8">
        <h2 class="t-title-m bold">{{ trPlain('Other ways', 'অন্য উপায়') }}<!-- bn-ok --></h2>
        <ul class="del-list t-body-m">
          <li>{{ trPlain('In the Android app: Profile → Delete account.', 'Android অ্যাপে: প্রোফাইল → অ্যাকাউন্ট মুছুন।') }}<!-- bn-ok --></li>
          <li>
            {{ trPlain("Can't log in? Send an email from your account's email address to", 'লগইন করতে না পারলে অ্যাকাউন্টের ইমেইল থেকে মেইল করুন:') }}<!-- bn-ok -->
            <a :href="mailto">{{ SUPPORT_EMAIL }}</a>{{ trPlain('. We delete the account within 30 days.', '। ৩০ দিনের মধ্যে অ্যাকাউন্ট মুছে ফেলা হবে।') }}<!-- bn-ok -->
          </li>
        </ul>
      </section>

      <section class="card col gap-8">
        <h2 class="t-title-m bold">{{ trPlain('What happens', 'কী হয়') }}<!-- bn-ok --></h2>
        <ul class="del-list t-body-m">
          <li>{{ trPlain('Right away: your listings (ambulance, pharmacy, blood bank, lab, chamber) are hidden, notifications stop and you are logged out everywhere.', 'সাথে সাথে: আপনার তালিকা (অ্যাম্বুলেন্স, ফার্মেসি, ব্লাড ব্যাংক, প্যাথলজি, চেম্বার) লুকানো হয়, নোটিফিকেশন বন্ধ হয়, সব জায়গা থেকে লগআউট হয়।') }}<!-- bn-ok --></li>
          <li>{{ trPlain('For 30 days you can cancel: just log in again (app or web) and everything comes back.', '৩০ দিন পর্যন্ত বাতিল করা যায়: আবার লগইন করলেই (অ্যাপে বা ওয়েবে) সব আগের মতো ফেরে।') }}<!-- bn-ok --></li>
          <li>{{ trPlain('After 30 days it is deleted for good: profile, photo, phone number, family members, blood requests and responses, questions, answers, tips, appointments, badge, caregiver links, pharmacy and lab requests, lab reports, payment records (TrxID), document photos, notifications and your businesses with their stock and books.', '৩০ দিন পর চিরতরে মুছে যায়: প্রোফাইল, ছবি, ফোন নম্বর, পরিবারের সদস্য, রক্তের রিকোয়েস্ট ও সাড়া, প্রশ্ন, উত্তর, টিপস, অ্যাপয়েন্টমেন্ট, ব্যাজ, কেয়ারগিভার সংযোগ, ফার্মেসি ও প্যাথলজির অনুরোধ, পরীক্ষার রিপোর্ট, টাকার রেকর্ড (TrxID), কাগজের ছবি, নোটিফিকেশন আর আপনার ব্যবসা (স্টক ও হিসাব-খাতাসহ)।') }}<!-- bn-ok --></li>
        </ul>
        <h3 class="t-title-s bold">{{ trPlain('What is kept', 'যা থাকে') }}<!-- bn-ok --></h3>
        <ul class="del-list t-body-m">
          <li>{{ trPlain("Prescriptions a doctor wrote stay in the doctor's records (no longer linked to you); a doctor's prescriptions stay with their patients.", 'ডাক্তারের লেখা প্রেসক্রিপশন ডাক্তারের রেকর্ডে থাকে (আপনার সাথে আর যুক্ত নয়); ডাক্তার মুছলে তাঁর লেখা প্রেসক্রিপশন রোগীদের কাছে থাকে।') }}<!-- bn-ok --></li>
          <li>{{ trPlain('If the Lifolo team suspended a listing, a one-way hash of its email, phone, licence or vehicle number stays so it cannot be listed again.', 'Lifolo টিম কোনো তালিকা বন্ধ করে থাকলে, আবার তালিকায় আসা ঠেকাতে তার ইমেইল, ফোন, লাইসেন্স বা গাড়ির নম্বরের একমুখী hash থাকে।') }}<!-- bn-ok --></li>
          <li>{{ trPlain('Server backups roll over: deleted data is gone from them within 90 days.', 'সার্ভারের ব্যাকআপ পুরোনো হয়ে মুছে যায়: মোছা তথ্য ৯০ দিনের মধ্যে ব্যাকআপ থেকেও চলে যায়।') }}<!-- bn-ok --></li>
        </ul>
        <p class="t-body-s muted">
          {{ trPlain('Full details:', 'বিস্তারিত:') }}<!-- bn-ok -->
          <a :href="privacyHref">{{ trPlain('Privacy policy', 'প্রাইভেসি পলিসি') }}</a>
        </p>
      </section>
    </div>
    <DeleteAccountDialog v-if="confirmOpen" @close="confirmOpen = false" @deleted="deleted" />
  </div>
</template>

<style scoped>
.del-page { padding-top: 8px; }
.del-list { margin: 0; padding-left: 20px; list-style: disc; display: flex; flex-direction: column; gap: 6px; }
.del-list a, .del-page a { color: var(--primary); overflow-wrap: anywhere; }
.del-done { border: 1px solid var(--outline-variant, var(--outline)); }
</style>
