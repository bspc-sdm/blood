<script setup lang="ts">
// /work/lab/new, /work/lab/edit
import { computed } from 'vue';
import FormPage from '@/shell/FormPage.vue';
import { useSession } from '@/lib/session';
import { tr } from '@/lib/i18n';
import { useNav } from '@/lib/nav';
import type { Lab } from './shared';
import WorkGate from './WorkGate.vue';
import LabEditor from './LabEditor.vue';

const s = useSession();
const nav = useNav();
const ready = computed(() => s.ready && !s.guest && s.workLoaded);
const existing = computed(() => s.work.lab as Lab | null);
const title = computed(() => (existing.value ? tr('My lab', 'আমার প্যাথলজি') : tr('Add my lab', 'আমার প্যাথলজি যোগ করুন')));
</script>

<template>
  <FormPage :title="title" :back-to="existing ? '/work/lab/info' : '/work/lab'">
    <WorkGate v-if="!ready" />
    <LabEditor v-else :key="existing?.id ?? 'new'" :existing="existing" @done="(flash) => nav('/work/lab/info', { state: { flash } })" />
  </FormPage>
</template>
