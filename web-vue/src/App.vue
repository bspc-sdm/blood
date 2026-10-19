<script setup lang="ts">
// পথ (route) — প্রতিটা অংশ আলাদা ফাইলে, দরকার হলে তবেই আনা হয় (প্রথম পাতা দ্রুত খোলে); পথের তালিকা router.ts-এ
import { RouterView, useRoute } from 'vue-router';
import AppShell from './shell/AppShell.vue';
import ErrorBoundary from './shell/ErrorBoundary.vue';
import { Loading, ToastHost } from './ui/kit';

const route = useRoute();
</script>

<template>
  <AppShell>
    <ErrorBoundary :reset-key="route.path">
      <RouterView v-slot="{ Component }">
        <component :is="Component" v-if="Component" />
        <Loading v-else />
      </RouterView>
    </ErrorBoundary>
  </AppShell>
  <ToastHost />
</template>
