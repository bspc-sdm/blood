<script setup lang="ts">
// প্রোফাইল (অ্যাপের Profile.kt → ProfileTab): ছবি, রক্তদানের যোগ্যতা, তথ্য, আর সব কাজের সারি।
// ডেস্কটপে দুই কলাম (বাঁয়ে প্রোফাইলের সারাংশ, ডানে মেনু); মোবাইলে অ্যাপের মতো এক কলাম।
import { Loading } from '@/ui/kit';
import { usePageTitle } from '@/shell/chrome';
import { tr } from '@/lib/i18n';
import { useSession } from '@/lib/session';
import GuestProfile from './GuestProfile.vue';
import MemberProfile from './MemberProfile.vue';

const s = useSession();
usePageTitle(() => tr('Profile', 'প্রোফাইল'));
</script>

<template>
  <Loading v-if="!s.ready" />
  <GuestProfile v-else-if="s.guest" />
  <Loading v-else-if="!s.profile" />
  <MemberProfile v-else :profile="s.profile" />
</template>
