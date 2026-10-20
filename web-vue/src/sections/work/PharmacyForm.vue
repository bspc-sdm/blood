<script setup lang="ts">
// /work/pharmacy/new, /work/pharmacy/edit
import { computed } from 'vue';
import FormPage from '@/shell/FormPage.vue';
import { useSession } from '@/lib/session';
import { tr } from '@/lib/i18n';
import { useNav } from '@/lib/nav';
import type { Pharmacy } from './shared';
import WorkGate from './WorkGate.vue';
import PharmacyEditor from './PharmacyEditor.vue';

const s = useSession();
const nav = useNav();
const ready = computed(() => s.ready && !s.guest && s.workLoaded);
const existing = computed(() => s.work.pharmacy as Pharmacy | null);
const title = computed(() => (existing.value ? tr('Edit pharmacy', 'ফার্মেসি বদলান') : tr('Open a pharmacy', 'ফার্মেসি খুলুন')));
</script>

<template>
  <FormPage :title="title" :back-to="existing ? '/work/pharmacy/shop' : '/work/pharmacy'">
    <WorkGate v-if="!ready" />
    <PharmacyEditor v-else :key="existing?.id ?? 'new'" :existing="existing" @done="(flash) => nav('/work/pharmacy/shop', { state: { flash } })" />
  </FormPage>
</template>
