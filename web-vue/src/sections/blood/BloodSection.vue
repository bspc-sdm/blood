<script setup lang="ts">
// হোম / রক্ত: আশেপাশের রিকোয়েস্ট (/), রিকোয়েস্টের বিস্তারিত (/request/:id), নতুন রিকোয়েস্ট (/request/new),
// আমার রিকোয়েস্ট (/my-requests, /my-requests/:id, /my-requests/:id/edit)।
// কয়েকটা পথে বসানো হয়, তাই কোন পাতা তা পুরো পথ (pathname) দেখে ঠিক হয়।
import { computed } from 'vue';
import { useRoute } from 'vue-router';
import { matchPath } from '@/lib/nav';
import NotFound from '@/pages/NotFound.vue';
import Home from './Home.vue';
import MyRequestsPage from './MyRequestsPage.vue';
import NewRequestPage from './form/NewRequestPage.vue';
import EditRequestPage from './form/EditRequestPage.vue';

const route = useRoute();
const page = computed(() => {
  const pathname = route.path;
  const m = (pattern: string) => matchPath({ path: pattern, end: true }, pathname);
  if (m('/')) return { k: 'home', id: null };
  if (m('/request/new')) return { k: 'new', id: null };
  const req = m('/request/:id');
  if (req) return { k: 'home', id: req.params.id };
  if (m('/my-requests')) return { k: 'mine', id: null };
  const edit = m('/my-requests/:id/edit');
  if (edit) return { k: 'edit', id: edit.params.id };
  const mine = m('/my-requests/:id');
  if (mine) return { k: 'mine', id: mine.params.id };
  return { k: 'none', id: null };
});
</script>

<template>
  <Home v-if="page.k === 'home'" :selected-id="page.id" />
  <NewRequestPage v-else-if="page.k === 'new'" />
  <MyRequestsPage v-else-if="page.k === 'mine'" :selected-id="page.id" />
  <EditRequestPage v-else-if="page.k === 'edit'" :id="page.id!" />
  <NotFound v-else />
</template>
