<script setup lang="ts">
// স্বাস্থ্য: আলোচনা, টিপস, পেশাদার ব্যাজ, আমার প্রেসক্রিপশন — /health/* আর /prescriptions/*
// (অ্যাপের MainScreen-এর "স্বাস্থ্য" ট্যাব: আলোচনা / টিপস; প্রেসক্রিপশন আলাদা পাতা)
import { computed, watch } from 'vue';
import { useRoute } from 'vue-router';
import { matchPath, useNav } from '@/lib/nav';
import NotFound from '@/pages/NotFound.vue';
import DiscussionPage from './DiscussionPage.vue';
import AskPage from './AskPage.vue';
import TipsPage from './TipsPage.vue';
import PostTipPage from './PostTipPage.vue';
import BadgePage from './BadgePage.vue';
import PrescriptionsPage from './PrescriptionsPage.vue';

const route = useRoute();
const nav = useNav();
const page = computed(() => {
  const p = route.path.replace(/\/+$/, '') || '/';
  const m = (pattern: string) => matchPath({ path: pattern, end: true }, p);
  if (p === '/health') return { k: 'redirect', id: null };
  if (m('/health/discussion')) return { k: 'discussion', id: null };
  const q = m('/health/question/:id');
  if (q) return { k: 'discussion', id: q.params.id ?? null };
  if (m('/health/ask')) return { k: 'ask', id: null };
  if (m('/health/tips')) return { k: 'tips', id: null };
  const t = m('/health/tip/:id');
  if (t) return { k: 'tips', id: t.params.id ?? null };
  if (m('/health/post-tip')) return { k: 'post-tip', id: null };
  if (m('/health/badge')) return { k: 'badge', id: null };
  if (m('/prescriptions')) return { k: 'rx', id: null };
  const r = m('/prescriptions/:id');
  if (r) return { k: 'rx', id: r.params.id ?? null };
  return { k: 'none', id: null };
});
watch(
  () => page.value.k,
  (k) => {
    if (k === 'redirect') nav('/health/discussion', { replace: true });
  },
  { immediate: true },
);
</script>

<template>
  <DiscussionPage v-if="page.k === 'discussion'" :id="page.id" />
  <AskPage v-else-if="page.k === 'ask'" />
  <TipsPage v-else-if="page.k === 'tips'" :id="page.id" />
  <PostTipPage v-else-if="page.k === 'post-tip'" />
  <BadgePage v-else-if="page.k === 'badge'" />
  <PrescriptionsPage v-else-if="page.k === 'rx'" :id="page.id" />
  <NotFound v-else-if="page.k === 'none'" />
</template>
