<script setup lang="ts">
// /work/ambulance/new, /work/ambulance/:id
import { computed } from 'vue';
import { RouterLink } from 'vue-router';
import FormPage from '@/shell/FormPage.vue';
import { useSession } from '@/lib/session';
import { tr } from '@/lib/i18n';
import { useNav } from '@/lib/nav';
import { MAX_AMBULANCES, myBusinessTitle, type Ambulance } from './shared';
import WorkGate from './WorkGate.vue';
import WorkEmpty from './WorkEmpty.vue';
import AmbulanceEditor from './AmbulanceEditor.vue';

const props = defineProps<{ id: string | null }>();
const s = useSession();
const nav = useNav();
const ready = computed(() => s.ready && !s.guest && s.workLoaded);
const existing = computed(() => (props.id ? (s.work.ambulances as Ambulance[]).find((a) => a.id === props.id) ?? null : null));
const title = computed(() => (existing.value ? tr('Edit ambulance', 'অ্যাম্বুলেন্স বদলান') : tr('Add ambulance', 'অ্যাম্বুলেন্স যোগ')));
</script>

<template>
  <FormPage :title="title" back-to="/work/ambulance">
    <WorkGate v-if="!ready" />
    <WorkEmpty v-else-if="id && !existing" icon="ambulance" :title="tr('Not found', 'পাওয়া যায়নি')">
      <RouterLink to="/work/ambulance" class="btn">{{ myBusinessTitle('ambulance') }}</RouterLink>
    </WorkEmpty>
    <WorkEmpty v-else-if="!id && s.work.ambulances.length >= MAX_AMBULANCES" icon="ambulance" :title="tr('Up to 10 ambulances per account', 'একটা অ্যাকাউন্টে সর্বোচ্চ ১০টা অ্যাম্বুলেন্স')" />
    <AmbulanceEditor v-else :existing="existing" @done="(flash) => nav('/work/ambulance', { state: { flash } })" />
  </FormPage>
</template>
