<script setup lang="ts">
// "সেবা" (অ্যাপের ServicesTab): অ্যাম্বুলেন্স / ফার্মেসি (+ ওষুধ খুঁজুন) / ব্লাড ব্যাংক / প্যাথলজি।
// পথ: /services → /services/ambulance; /services/:kind; /services/:kind/:id; /services/pharmacy/medicine;
// /services/pharmacy/prescriptions(/:id) — আমার প্রেসক্রিপশন (পুরোনো /prescriptions/* এখানে redirect)
// নিজের ব্যবসা এখানে নয় — "আমার কাজ" (/work)।
import { computed, watch } from 'vue';
import { useRoute } from 'vue-router';
import { usePageTitle } from '@/shell/chrome';
import { tr } from '@/lib/i18n';
import { matchPath, useNav } from '@/lib/nav';
import { isKind, type Kind } from './data';
import Directory from './Directory.vue';
import MedFind from './MedFind.vue';
import RequestPage from './RequestPage.vue';
import PrescriptionsPage from '../health/PrescriptionsPage.vue';

usePageTitle(() => tr('Services', 'সেবা'));
const route = useRoute();
const nav = useNav();
const page = computed<{ k: 'med' } | { k: 'rx'; id: string | null } | { k: 'req'; id: string } | { k: 'dir'; kind: Kind; id: string | null } | { k: 'redirect' }>(() => {
  const pathname = route.path;
  if (matchPath('/services/pharmacy/medicine', pathname)) return { k: 'med' };
  if (matchPath('/services/pharmacy/prescriptions', pathname)) return { k: 'rx', id: null };
  const rx = matchPath('/services/pharmacy/prescriptions/:id', pathname);
  if (rx?.params.id) return { k: 'rx', id: rx.params.id };
  // অ্যাম্বুলেন্স ডাকার অপেক্ষার পাতা (২.২৮.০)
  const req = matchPath('/services/ambulance/call/:id', pathname);
  if (req?.params.id) return { k: 'req', id: req.params.id };
  const detail = matchPath('/services/:kind/:id', pathname);
  const kind = (detail ?? matchPath('/services/:kind', pathname))?.params.kind;
  if (!isKind(kind)) return { k: 'redirect' };
  return { k: 'dir', kind, id: detail?.params.id ?? null };
});
watch(
  page,
  (p) => {
    if (p.k === 'redirect') nav('/services/ambulance', { replace: true });
  },
  { immediate: true },
);
</script>

<template>
  <MedFind v-if="page.k === 'med'" />
  <PrescriptionsPage v-else-if="page.k === 'rx'" :id="page.id" />
  <RequestPage v-else-if="page.k === 'req'" :id="page.id" :key="page.id" />
  <Directory v-else-if="page.k === 'dir'" :key="page.kind" :kind="page.kind" :id="page.id" />
</template>
