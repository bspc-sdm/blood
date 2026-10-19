<script setup lang="ts">
// /work/add — কোন ব্যবসা যোগ করবেন (অ্যাপের AddBusinessDialog)
import { RouterLink } from 'vue-router';
import { Icon, Loading, Tag } from '@/ui/kit';
import FormPage from '@/shell/FormPage.vue';
import { useSession } from '@/lib/session';
import { num, tr } from '@/lib/i18n';
import { useNav } from '@/lib/nav';
import { KINDS, MAX_AMBULANCES, MAX_BLOODBANKS, businessIcon, businessName, type Kind } from './shared';

const s = useSession();
const nav = useNav();
const count = (k: Kind) => {
  const w = s.work;
  return k === 'ambulance' ? w.ambulances.length : k === 'pharmacy' ? (w.pharmacy ? 1 : 0) : k === 'bloodbank' ? w.bloodBanks.length : w.lab ? 1 : 0;
};
const max = (k: Kind) => (k === 'ambulance' ? MAX_AMBULANCES : k === 'bloodbank' ? MAX_BLOODBANKS : 1);
// যেটা আগে থেকে আছে (একটাই হয়) বা সীমা ভরা — নিজের পাতাই খোলে
const target = (k: Kind) => (count(k) >= max(k) ? `/work/${k}` : `/work/${k}/new`);
const desc = (k: Kind) =>
  k === 'ambulance' ? tr('Patients nearby call you directly', 'কাছের রোগীরা সরাসরি ফোন করবেন')
    : k === 'pharmacy' ? tr('Licensed shop — calls, medicine requests, prescriptions', 'লাইসেন্সধারী দোকান — ফোন, ওষুধ খোঁজা, প্রেসক্রিপশন')
      : k === 'bloodbank' ? tr('Government, private or volunteer', 'সরকারি, বেসরকারি বা স্বেচ্ছাসেবী')
        : tr('Tests from prescriptions, reports in the app', 'প্রেসক্রিপশনের পরীক্ষা, রিপোর্ট অ্যাপেই');
function pick(k: Kind) {
  s.requireLogin(tr('Log in to add your business', 'আপনার ব্যবসা যোগ করতে লগইন করুন'), () => nav(target(k)));
}
</script>

<template>
  <FormPage :title="tr('What is your business?', 'আপনার কোন ব্যবসা?')" back-to="/work">
    <Loading v-if="!s.guest && !s.workLoaded && !s.workError" />
    <div v-else class="work-choose">
      <button v-for="k in KINDS" :key="k" type="button" class="work-choice" @click="pick(k)">
        <span class="work-ic"><Icon :name="businessIcon(k)" /></span>
        <span class="col gap-4 grow">
          <span class="t-title-m">{{ businessName(k) }}</span>
          <span class="t-body-s muted">{{ desc(k) }}</span>
          <Tag v-if="count(k) > 0" tone="good">{{ max(k) > 1 ? tr(`Added ${count(k)}`, `${num(count(k))}টা যোগ করা আছে`) : tr('Added', 'যোগ করা আছে') }}</Tag>
        </span>
        <Icon name="chevron_right" class="work-chev" />
      </button>
    </div>
    <p class="t-body-s muted">
      {{ tr('Doctors, nurses and health experts: apply for a professional badge in your profile.', 'ডাক্তার, নার্স ও স্বাস্থ্য বিশেষজ্ঞ: প্রোফাইলের “পেশাদার ব্যাজ” থেকে আবেদন করুন।') }}
      <RouterLink to="/health/badge" class="c-primary semi">{{ tr('Professional badge', 'পেশাদার ব্যাজ') }}</RouterLink>
    </p>
  </FormPage>
</template>
