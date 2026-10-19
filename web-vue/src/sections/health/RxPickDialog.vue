<script setup lang="ts">
// রোগীর চালু প্রেসক্রিপশন — ডাক্তার, তারিখ, আর পরীক্ষা (forLab) বা ওষুধের নাম
import { computed, watch } from 'vue';
import { Dialog, EmptyState, ListRow, Loading } from '@/ui/kit';
import { call, errorText } from '@/lib/api';
import { useQ } from '@/lib/query';
import { tr, useLang } from '@/lib/i18n';
import { formatDate } from '@/lib/format';
import { useSession } from '@/lib/session';
import { useCareMode } from '@/lib/care';
import type { Rx } from './rx';
import SendError from './SendError.vue';

const props = defineProps<{ forLab: boolean; onOnly?: (rx: Rx) => void }>();
const emit = defineEmits<{ close: []; pick: [rx: Rx] }>();
const lang = useLang();
const care = useCareMode();
const s = useSession();
// প্রেসক্রিপশনের পাতার একই চাবি — একবার আনা থাকলে আবার নয়
const q = useQ<Rx[]>(() => ({
  queryKey: ['rx_mine', care.value?.uid ?? null, lang.value, s.uid],
  queryFn: () => call<{ items?: Rx[] }>('rx_mine').then((r) => r.items ?? []),
}));
const list = computed(() => (q.data ?? []).filter((r) => r.status !== 'cancelled' && (props.forLab ? (r.tests ?? []).length > 0 : (r.medicines ?? []).length > 0)));
const only = computed(() => (q.isSuccess && list.value.length === 1 ? list.value[0] : null));
watch(
  () => only.value?.id,
  () => {
    if (only.value && props.onOnly) props.onOnly(only.value);
  },
  { immediate: true },
);
</script>

<template>
  <Dialog v-if="!(only && onOnly)" :title="tr('Which prescription?', 'কোন প্রেসক্রিপশন পাঠাবেন?')" @close="emit('close')">
    <Loading v-if="q.isPending" />
    <SendError v-else-if="q.isError" :text="errorText(q.error)" />
    <EmptyState v-else-if="!list.length" icon="list_alt" :title="forLab ? tr('No prescription with tests', 'পরীক্ষা লেখা কোনো প্রেসক্রিপশন নেই') : tr('No prescription with medicines', 'ওষুধ লেখা কোনো প্রেসক্রিপশন নেই')" />
    <div v-else class="col gap-4">
      <ListRow v-for="r in list" :key="r.id" :icon="forLab ? 'local_hospital' : 'medication'" :title="r.doctor?.name || tr('Doctor', 'ডাক্তার')" @click="emit('pick', r)">
        <template #sub>{{ formatDate(r.createdAt) }}<br />{{ forLab ? (r.tests ?? []).join(', ') : (r.medicines ?? []).map((m) => m.name).join(', ') }}</template>
      </ListRow>
    </div>
  </Dialog>
</template>
