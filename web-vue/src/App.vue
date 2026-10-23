<script setup lang="ts">
// পথ (route) — প্রতিটা অংশ আলাদা ফাইলে, দরকার হলে তবেই আনা হয় (প্রথম পাতা দ্রুত খোলে); পথের তালিকা router.ts-এ
import { RouterView, useRoute } from 'vue-router';
import AppShell from './shell/AppShell.vue';
import AccountPaused from './shell/AccountPaused.vue';
import ErrorBoundary from './shell/ErrorBoundary.vue';
import { Loading, ToastHost } from './ui/kit';
import { useSession } from './lib/session';
import CallLayer from './calls/CallLayer.vue';
import ConsentHost from './sections/consent/ConsentHost.vue';

const route = useRoute();
const s = useSession();
</script>

<template>
  <!-- অ্যাকাউন্ট সাময়িক বন্ধ: পুরো ওয়েবের বদলে "আবার চালু করুন" -->
  <!-- (মোছার পাতা খোলা যায় — বন্ধ অবস্থাতেও মোছা চলে) -->
  <AccountPaused v-if="s.deactivated && route.path !== '/delete-account'" />
  <AppShell v-else>
    <ErrorBoundary :reset-key="route.path">
      <RouterView v-slot="{ Component }">
        <component :is="Component" v-if="Component" />
        <Loading v-else />
      </RouterView>
    </ErrorBoundary>
  </AppShell>
  <!-- অ্যাপে কল: আসা কলের ব্যানার আর কলের পর্দা (লগইন থাকলে) -->
  <CallLayer v-if="!s.deactivated" />
  <!-- স্বাস্থ্য তথ্যের সম্মতি (backend/docs/CONSENT.md): 428-এর পর বা স্বাস্থ্যের পাতা খোলায় -->
  <ConsentHost v-if="!s.guest" />
  <ToastHost />
</template>
