<script setup lang="ts">
// অ্যাকাউন্ট অংশ: /profile, /profile/edit, /settings, /inbox[/:id], /help[/new|/:id], /care, /login
import { computed } from 'vue';
import { useRoute } from 'vue-router';
import { matchPath } from '@/lib/nav';
import NotFound from '@/pages/NotFound.vue';
import ProfilePage from './ProfilePage.vue';
import EditProfilePage from './EditProfilePage.vue';
import SettingsPage from './SettingsPage.vue';
import InboxPage from './InboxPage.vue';
import HelpPage from './HelpPage.vue';
import CarePage from './CarePage.vue';
import LoginPage from './LoginPage.vue';

const route = useRoute();
const page = computed(() => {
  const p = route.path.length > 1 ? route.path.replace(/\/+$/, '') : route.path;
  const is = (pattern: string) => !!matchPath({ path: pattern, end: true }, p);
  if (is('/profile')) return 'profile';
  if (is('/profile/edit')) return 'edit';
  if (is('/settings')) return 'settings';
  if (is('/inbox') || is('/inbox/:id')) return 'inbox';
  if (is('/help') || is('/help/:id')) return 'help';
  if (is('/care')) return 'care';
  if (is('/login')) return 'login';
  return 'none';
});
</script>

<template>
  <ProfilePage v-if="page === 'profile'" />
  <EditProfilePage v-else-if="page === 'edit'" />
  <SettingsPage v-else-if="page === 'settings'" />
  <InboxPage v-else-if="page === 'inbox'" />
  <HelpPage v-else-if="page === 'help'" />
  <CarePage v-else-if="page === 'care'" />
  <LoginPage v-else-if="page === 'login'" />
  <NotFound v-else />
</template>
