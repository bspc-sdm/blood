<script setup lang="ts">
// প্রোফাইল — অতিথি
import { Button, Icon, ListRow } from '@/ui/kit';
import { tr } from '@/lib/i18n';
import { useSession } from '@/lib/session';
import { useNav } from '@/lib/nav';
import AcctMenu from './AcctMenu';
import BecomeDonorCard from './BecomeDonorCard.vue';

const s = useSession();
const nav = useNav();
</script>

<template>
  <div class="acct-page">
    <h1 class="acct-mhead t-headline">{{ tr('Profile', 'প্রোফাইল') }}</h1>
    <div class="acct-narrow col gap-12">
      <section class="card acct-summary">
        <span class="acct-photo static"><Icon name="person_filled" :size="56" /></span>
        <p class="t-body-m muted">{{ tr('We will email you a code. No password needed.', 'ইমেইলে একটা কোড যাবে, পাসওয়ার্ড লাগবে না।') }}</p>
        <Button @click="s.login()">{{ tr('Log in', 'লগইন') }}</Button>
      </section>
      <BecomeDonorCard @click="s.requireLogin(tr('Log in to become a blood donor', 'রক্তদাতা হতে লগইন করুন'), () => {}, { donor: true })" />
      <AcctMenu>
        <ListRow icon="settings_outline" :title="tr('App settings', 'অ্যাপ সেটিংস')" :sub="tr('Language, theme, notifications, area', 'ভাষা, থিম, নোটিফিকেশন, এলাকা')" :on-click="() => nav('/settings')" />
        <ListRow icon="help" :title="tr('Help & appeals', 'সাহায্য ও আপিল')" :on-click="() => nav('/help')" />
        <ListRow
          icon="storefront"
          :title="tr('Add your business', 'আপনার ব্যবসা যোগ করুন')"
          :sub="tr('Ambulance, pharmacy, blood bank, lab', 'অ্যাম্বুলেন্স, ফার্মেসি, ব্লাড ব্যাংক, প্যাথলজি')"
          :on-click="() => s.requireLogin(tr('Log in to add your business', 'আপনার ব্যবসা যোগ করতে লগইন করুন'), () => nav('/work/add'))"
        />
      </AcctMenu>
    </div>
  </div>
</template>
