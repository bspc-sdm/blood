<script setup lang="ts">
// "আমার কাজ" (/work/*) — নিজের অ্যাম্বুলেন্স, ফার্মেসি, ব্লাড ব্যাংক, প্যাথলজি আর পেশাদারের চেম্বার (অ্যাপের Work.kt)
import { computed, defineAsyncComponent } from 'vue';
import { useRoute } from 'vue-router';
import { Loading } from '@/ui/kit';
import { matchPath } from '@/lib/nav';
import NotFound from '@/pages/NotFound.vue';

const lazy = (f: () => Promise<any>) => defineAsyncComponent({ loader: f, loadingComponent: Loading });
const Pro = lazy(() => import('../appointment/pro/ProfessionalWorkspace.vue'));

const route = useRoute();
const page = computed(() => {
  const p = route.path;
  if (matchPath({ path: '/work/pro/*', end: false }, p)) return { k: 'pro' };
  return { k: 'none' };
});
</script>

<template>
  <Pro v-if="page.k === 'pro'" />
  <NotFound v-else />
</template>
