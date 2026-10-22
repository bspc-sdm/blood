<script setup lang="ts">
// /delete-account — পাবলিক পাতা (Google Play-র "Delete account URL", app/PLAY-STORE.md §৮): লগইন ছাড়াই খোলে।
// প্রোফাইল আর সেটিংসের "অ্যাকাউন্ট মুছুন"-ও এখানে আসে (ছোট ডায়ালগ নয়, পুরো পাতা)।
// ওপরে বিকল্প "মুছে না ফেলে সাময়িক বন্ধ রাখুন" (/deactivate-account); কী মুছবে, কী থাকে, ৩০ দিনে ফেরানো আর আসল তারিখ;
// লগইন থাকলে "আমি বুঝেছি" টিক দিয়ে মোছা (DeleteAccountConfirm), না থাকলে "লগইন করে মুছুন" আর ইমেইলের পথ।
// লেখার সারাংশ PRIVACY.md-এর "অ্যাকাউন্ট মোছা" থেকে — ওটা বদলালে এখানেও মেলান। নতুন লেখা, তাই বাংলা লাইনে bn-ok।
import { computed, nextTick, ref, watch } from 'vue';
import { Button, Icon, Loading } from '@/ui/kit';
import { usePageTitle } from '@/shell/chrome';
import { formatDayOf } from '@/lib/format';
import { trPlain } from '@/lib/i18n';
import { useSession } from '@/lib/session';
import { useNav, useSearchParams } from '@/lib/nav';
import { deleteDateFrom } from '@/lib/account';
import DeleteAccountConfirm from '@/sections/account/DeleteAccountConfirm.vue';
import { SUPPORT_EMAIL } from '@/sections/account/util';

const s = useSession();
const nav = useNav();
const [params] = useSearchParams();
usePageTitle(() => trPlain('Delete account', 'অ্যাকাউন্ট মুছুন')); // bn-ok

const done = ref<{ deleteAt: number | null; email: string } | null>(null);
// আজ মুছলে কবে চিরতরে মুছবে (ঢাকার তারিখ)
const lastDay = computed(() => formatDayOf(deleteDateFrom(Date.now())));
const doneDay = computed(() => (done.value?.deleteAt ? formatDayOf(done.value.deleteAt) : lastDay.value));

// "লগইন করে মুছুন" চাপলে (বা ?confirm=1) লগইন শেষ হলেই নিশ্চিত করার অংশে নিয়ে যাওয়া
const confirmBox = ref<HTMLElement | null>(null);
const wantDelete = ref(params.value.get('confirm') === '1');
watch(
  () => [s.ready, s.guest, s.pending] as const,
  async ([ready, guest, pending]) => {
    if (ready && !guest && !pending && wantDelete.value && !done.value) {
      wantDelete.value = false;
      await nextTick();
      confirmBox.value?.scrollIntoView({ block: 'center' });
      confirmBox.value?.querySelector('input')?.focus();
    }
  },
  { immediate: true },
);

function loginToDelete() {
  wantDelete.value = true;
  s.login();
}
function deleted(deleteAt: number | null, email: string) {
  done.value = { deleteAt, email };
  window.scrollTo(0, 0);
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

      <!-- মোছার অনুরোধ হয়ে গেছে: এরপর কী হবে, ইমেইল গেছে, লগআউট -->
      <section v-if="done" class="card del-done col gap-8" role="status" data-testid="delete-done">
        <div class="row gap-8"><Icon name="check_circle" :size="24" /><h2 class="t-title-m bold">{{ trPlain('Deletion requested', 'মোছার অনুরোধ নেওয়া হয়েছে') }}<!-- bn-ok --></h2></div>
        <p v-if="done.deleteAt" class="t-body-m">{{ trPlain(`Your account will be deleted on ${doneDay}.`, `আপনার অ্যাকাউন্ট ${doneDay} তারিখে মুছে যাবে।`) }}<!-- bn-ok --></p>
        <h3 class="t-title-s bold">{{ trPlain('What happens next', 'এরপর কী হবে') }}<!-- bn-ok --></h3>
        <ul class="del-list t-body-m">
          <li>{{ trPlain('Now: your listings are hidden, notifications stop and you are logged out everywhere.', 'এখন: আপনার তালিকা লুকানো, নোটিফিকেশন বন্ধ, সব জায়গা থেকে লগআউট।') }}<!-- bn-ok --></li>
          <li>{{ trPlain(`Until ${doneDay}: log in again (app or web) and the deletion is cancelled — everything comes back.`, `${doneDay} পর্যন্ত: আবার লগইন করলেই (অ্যাপে বা ওয়েবে) মোছা বাতিল, সব আগের মতো ফিরবে।`) }}<!-- bn-ok --></li>
          <li>{{ trPlain(`After ${doneDay}: deleted for good — it cannot be brought back.`, `${doneDay}-এর পর: চিরতরে মুছে যাবে — আর ফেরানো যাবে না।`) }}<!-- bn-ok --></li>
        </ul>
        <p class="t-body-m" data-testid="delete-email">
          <template v-if="done.email">{{ trPlain(`We sent an email to ${done.email}: the date, what will be deleted and how to cancel.`, `${done.email} ঠিকানায় একটা ইমেইল পাঠানো হয়েছে: কোন তারিখে মুছবে, কী কী মুছবে আর কীভাবে বাতিল করবেন।`) }}<!-- bn-ok --></template>
          <template v-else>{{ trPlain('We sent an email to your account email: the date, what will be deleted and how to cancel.', 'আপনার অ্যাকাউন্টের ইমেইলে একটা ইমেইল পাঠানো হয়েছে: কোন তারিখে মুছবে, কী কী মুছবে আর কীভাবে বাতিল করবেন।') }}<!-- bn-ok --></template>
        </p>
        <p class="t-body-m">{{ trPlain('You have been logged out on every device. Log in again before then if you change your mind — that cancels the deletion.', 'সব ফোন আর ব্রাউজার থেকে লগআউট হয়েছে। মত বদলালে এর আগে আবার লগইন করুন — তাতে মোছা বাতিল হবে।') }}<!-- bn-ok --></p>
        <div><Button variant="tonal" icon="nav_home" @click="nav('/', { replace: true })">{{ trPlain('Go to home', 'হোমে যান') }}<!-- bn-ok --></Button></div>
      </section>

      <template v-else>
        <!-- বিকল্প: না মুছে সাময়িক বন্ধ -->
        <section v-if="!s.deactivated" class="card del-alt col gap-8" data-testid="pause-instead">
          <h2 class="t-title-m bold">{{ trPlain('Just need a break?', 'শুধু কিছুদিনের বিরতি চান?') }}<!-- bn-ok --></h2>
          <p class="t-body-m">{{ trPlain('Pause your account instead: your profile, donor details and listings are hidden and notifications stop — but nothing is deleted, and there is no time limit. Log in and turn it back on whenever you like.', 'মুছে না ফেলে সাময়িক বন্ধ রাখুন: প্রোফাইল, রক্তদাতার তথ্য আর আপনার তালিকা লুকানো থাকবে, নোটিফিকেশন আসবে না — কিন্তু কিছুই মুছবে না, সময়ের সীমাও নেই। যখন খুশি লগইন করে আবার চালু করুন।') }}<!-- bn-ok --></p>
          <div><Button variant="filled" icon="schedule" @click="nav('/deactivate-account')">{{ trPlain('Pause instead of deleting', 'মুছে না ফেলে সাময়িক বন্ধ রাখুন') }}<!-- bn-ok --></Button></div>
        </section>

        <!-- কী মুছবে, কী থাকে -->
        <section class="card col gap-8">
          <h2 class="t-title-m bold">{{ trPlain('What will be deleted', 'যা মুছে যাবে') }}<!-- bn-ok --></h2>
          <ul class="del-list t-body-m" data-testid="delete-what">
            <li>{{ trPlain('Profile and photo', 'প্রোফাইল ও ছবি') }}<!-- bn-ok --></li>
            <li>{{ trPlain('Phone number', 'ফোন নম্বর') }}</li>
            <li>{{ trPlain('Blood requests', 'রক্তের অনুরোধ') }}<!-- bn-ok --></li>
            <li>{{ trPlain('Prescriptions and lab reports', 'প্রেসক্রিপশন ও ল্যাব রিপোর্ট') }}<!-- bn-ok --></li>
            <li>{{ trPlain('Appointments', 'অ্যাপয়েন্টমেন্ট') }}</li>
            <li>{{ trPlain('Questions, answers and tips', 'প্রশ্নোত্তর ও টিপস') }}<!-- bn-ok --></li>
            <li>{{ trPlain('Your shop, lab, ambulance and chamber listings, and your shop books', 'আপনার দোকান/ল্যাব/অ্যাম্বুলেন্স/চেম্বারের তালিকা আর দোকানের হিসাব') }}<!-- bn-ok --></li>
          </ul>
          <p class="t-body-s muted">{{ trPlain('Also: family members, badge, caregiver links, verification document photos, notifications and help messages.', 'এছাড়া: পরিবারের সদস্য, ব্যাজ, কেয়ারগিভার সংযোগ, যাচাইয়ের কাগজের ছবি, নোটিফিকেশন আর সাহায্যের বার্তা।') }}<!-- bn-ok --></p>
          <h3 class="t-title-s bold">{{ trPlain('What is kept', 'যা থাকে') }}<!-- bn-ok --></h3>
          <ul class="del-list t-body-m" data-testid="delete-kept">
            <li>{{ trPlain("A prescription a doctor wrote stays in the doctor's records, no longer linked to your account. If you are a doctor, the prescriptions you wrote stay with your patients.", 'ডাক্তারের লেখা প্রেসক্রিপশনের রেকর্ড ডাক্তারের কাছে থাকে, কিন্তু আপনার অ্যাকাউন্টের সাথে আর যুক্ত থাকে না। আপনি ডাক্তার হলে আপনার লেখা প্রেসক্রিপশন রোগীদের কাছে থাকে।') }}<!-- bn-ok --></li>
            <li>{{ trPlain("Sales a pharmacy recorded in its own books (without your name) stay with that shop.", 'কোনো ফার্মেসি নিজের হিসাব-খাতায় যে বিক্রি তুলেছে (আপনার নাম ছাড়া), তা সেই দোকানের কাছে থাকে।') }}<!-- bn-ok --></li>
            <li>{{ trPlain('If the Lifolo team suspended a listing, a one-way hash of its email, phone, licence or vehicle number stays so it cannot be listed again.', 'Lifolo টিম কোনো তালিকা বন্ধ করে থাকলে, আবার তালিকায় আসা ঠেকাতে তার ইমেইল, ফোন, লাইসেন্স বা গাড়ির নম্বরের একমুখী hash থাকে।') }}<!-- bn-ok --></li>
            <li>{{ trPlain('Deleted data can remain in server backups for 14 days and in off-site encrypted copies for up to 90 days, and is then removed automatically.', 'মোছা তথ্য সার্ভারের ব্যাকআপে ১৪ দিন আর বাইরের এনক্রিপ্ট করা কপিতে সর্বোচ্চ ৯০ দিন থাকতে পারে, তারপর নিজে থেকে মুছে যায়।') }}<!-- bn-ok --></li>
          </ul>
        </section>

        <!-- ৩০ দিনে ফেরানো, আসল তারিখ -->
        <section class="card col gap-8" data-testid="delete-recovery">
          <h2 class="t-title-m bold">{{ trPlain('If you change your mind', 'মত বদলালে') }}<!-- bn-ok --></h2>
          <p class="t-body-m">{{ trPlain('Log in again within 30 days to cancel the deletion — everything comes back as it was. After 30 days it is deleted for good — it cannot be brought back.', '৩০ দিনের মধ্যে আবার লগইন করলে মোছা বাতিল হবে, সব আগের মতো ফিরবে। ৩০ দিন পর চিরতরে মুছে যাবে — আর ফেরানো যাবে না।') }}<!-- bn-ok --></p>
          <p class="t-body-m del-date" data-testid="delete-date">{{ trPlain(`Delete today and you can undo it until ${lastDay}; after that it is gone for good.`, `আজ মুছলে ${lastDay} পর্যন্ত ফেরানো যাবে, তারপর চিরতরে মুছে যাবে।`) }}<!-- bn-ok --></p>
          <p class="t-body-s muted">{{ trPlain('Right away: your listings are hidden, notifications stop and you are logged out on every phone and browser.', 'সাথে সাথে: আপনার তালিকা লুকানো হয়, নোটিফিকেশন বন্ধ হয়, সব ফোন আর ব্রাউজার থেকে লগআউট হয়।') }}<!-- bn-ok --></p>
        </section>

        <!-- এখানে মোছা -->
        <section ref="confirmBox" class="card col gap-8">
          <h2 class="t-title-m bold">{{ trPlain('Delete it here', 'এখানেই মুছুন') }}<!-- bn-ok --></h2>
          <Loading v-if="!s.ready" />
          <template v-else-if="s.guest">
            <p class="t-body-m">{{ trPlain('Log in with your email (we send a 6-digit code), then confirm.', 'ইমেইল দিয়ে লগইন করুন (৬ সংখ্যার কোড যাবে), তারপর নিশ্চিত করুন।') }}<!-- bn-ok --></p>
            <div><Button variant="danger" icon="delete" @click="loginToDelete">{{ trPlain('Log in and delete', 'লগইন করে মুছুন') }}<!-- bn-ok --></Button></div>
          </template>
          <template v-else>
            <p class="t-body-m">{{ trPlain('Logged in as', 'লগইন করা আছে:') }}<!-- bn-ok --> <strong>{{ s.email }}</strong></p>
            <DeleteAccountConfirm @deleted="deleted" />
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
          <p class="t-body-s muted">
            {{ trPlain('Full details:', 'বিস্তারিত:') }}<!-- bn-ok -->
            <a :href="privacyHref">{{ trPlain('Privacy policy', 'প্রাইভেসি পলিসি') }}</a>
          </p>
        </section>
      </template>
    </div>
  </div>
</template>

<style scoped>
.del-page { padding-top: 8px; }
.del-list { margin: 0; padding-left: 20px; list-style: disc; display: flex; flex-direction: column; gap: 6px; }
.del-list a, .del-page a { color: var(--primary); overflow-wrap: anywhere; }
.del-done { border: 1px solid var(--outline-variant, var(--outline)); }
.del-alt { background: var(--secondary-container); color: var(--on-secondary-container); }
.del-date { font-weight: 600; }
</style>
