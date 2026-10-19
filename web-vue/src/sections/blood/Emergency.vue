<script setup lang="ts">
// জরুরি রক্তের আহ্বান (Lifolo টিম) — অ্যাপের Emergency.kt।
// হোমের উপরে: কোন গ্রুপের কত দরকার, কতজন আসছেন; নিজের গ্রুপ দরকার হলে "আসছি"।
import { computed, ref } from 'vue';
import { InfoText } from '@/ui/kit';
import { call } from '@/lib/api';
import { useSession } from '@/lib/session';
import { useQ } from '@/lib/query';
import { KEY } from './data';
import { parseAppeal, type Appeal, type Ask } from './emergency';
import AskCard from './EmergencyAsk.vue';
import EmergencyCard from './EmergencyCard.vue';

const props = defineProps<{ district?: string | null }>();
const s = useSession();
const thanks = ref<string | null>(null);
const q = useQ<{ items: Appeal[]; ask: Ask[] }>(() => ({
  queryKey: [KEY, 'emergencies', s.uid, s.guest ? props.district ?? '' : ''],
  queryFn: async () => {
    const o = await call<{ items?: any[]; ask?: any[] }>('emergencies_active', s.guest && props.district ? { district: props.district } : {});
    return { items: (o.items ?? []).map(parseAppeal), ask: (o.ask ?? []) as Ask[] };
  },
  enabled: s.ready,
  staleTime: 30_000,
}));
const items = computed(() => q.data?.items ?? []);
const ask = computed(() => q.data?.ask ?? []);
const reload = () => q.refetch();
function onDone(m: string) {
  thanks.value = m;
  reload();
}
</script>

<template>
  <div v-if="items.length || ask.length || thanks" class="col blood-emg-wrap">
    <InfoText :text="thanks" />
    <AskCard v-for="a in ask" :key="a.id" :a="a" @done="onDone" />
    <EmergencyCard v-for="e in items" :key="e.id" :e="e" @donated="(m: string) => (thanks = m)" @changed="reload" />
  </div>
</template>
