<script setup lang="ts">
// পথ (route) — প্রতিটা অংশ আলাদা ফাইলে, দরকার হলে তবেই আনা হয় (প্রথম পাতা দ্রুত খোলে); পথের তালিকা router.ts-এ
import { RouterView, useRoute } from 'vue-router';
import AppShell from './shell/AppShell.vue';
import AccountPaused from './shell/AccountPaused.vue';
import ErrorBoundary from './shell/ErrorBoundary.vue';
import { Loading, ToastHost } from './ui/kit';
import { useSession } from './lib/session';

const route = useRoute();
const s = useSession();
</script>

<template>
  <!-- অ্যাকাউন্ট সাময়িক বন্ধ: পুরো ওয়েবের বদলে "আবার চালু করুন" -->
  <AccountPaused v-if="s.deactivated" />
  <AppShell v-else>
    <ErrorBoundary :reset-key="route.path">
      <RouterView v-slot="{ Component }">
        <component :is="Component" v-if="Component" />
        <Loading v-else />
      </RouterView>
    </ErrorBoundary>
  </AppShell>
  <ToastHost />
</template>
