<script setup lang="ts">
// /invoices/* — রোগীর রসিদ (তালিকা, একটা রসিদ, আমার খরচ); /v/:code — রসিদ যাচাই (router.ts সরাসরি VerifyPage দেয়)
import { computed } from 'vue';
import { useRoute } from 'vue-router';
import { matchPath } from '@/lib/nav';
import NotFound from '@/pages/NotFound.vue';
import MyInvoicesPage from './MyInvoicesPage.vue';

const route = useRoute();
const page = computed(() => {
  const p = route.path.replace(/\/+$/, '') || '/';
  if (p === '/invoices') return { k: 'list' as const, id: null };
  if (p === '/invoices/expenses') return { k: 'expenses' as const, id: null };
  const m = matchPath({ path: '/invoices/:id', end: true }, p);
  if (m) return { k: 'list' as const, id: m.params.id ?? null };
  return { k: 'none' as const, id: null };
});
</script>

<template>
  <MyInvoicesPage v-if="page.k !== 'none'" :id="page.id" :tab="page.k" />
  <NotFound v-else />
</template>
