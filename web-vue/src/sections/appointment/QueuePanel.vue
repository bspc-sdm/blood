<script setup lang="ts">
// আজকের সিরিয়ালের সারি চালানো; doctorUid = সহকারী কার হয়ে (ডাক্তার নিজে হলে null)
import { ErrorText } from '@/ui/kit';
import { errorText } from '@/lib/api';
import { useCall } from '@/lib/query';
import { REFRESH, type QueueChamber } from './queue';
import QueueChamberCard from './QueueChamberCard.vue';

const props = defineProps<{ doctorUid?: string | null }>();
const q = useCall<{ role: string; chambers: QueueChamber[] }>('queue_today', () => (props.doctorUid ? { doctorUid: props.doctorUid } : {}), { refetchInterval: REFRESH, staleTime: 10_000 });
</script>

<template>
  <div v-if="q.data?.chambers.length" class="col gap-12">
    <QueueChamberCard v-for="c in q.data.chambers" :key="c.key" :c="c" :many="q.data.chambers.length > 1" :doctor-uid="doctorUid ?? null" :reload="() => q.refetch()" />
  </div>
  <ErrorText v-else-if="q.error" :text="errorText(q.error)" />
</template>
