<script setup lang="ts">
import { computed } from 'vue';
import { DirectoryCard } from '@/ui/kit';
import { num, tr } from '@/lib/i18n';
import { formatDate } from '@/lib/format';
import { patientLine, type Rx } from './rx';
import { isNewType, newTypeLabel, systemTag } from '@/lib/practice';
import { practitionerLabel } from '@/lib/titleGuard';
import RxStatus from './RxStatus.vue';

const props = defineProps<{ rx: Rx; selected: boolean; onClick: () => void }>();
const meds = computed(() => props.rx.medicines ?? []);
const cancelled = computed(() => props.rx.status === 'cancelled');
// অবস্থা সার্ভার থেকে এলে (state) পরীক্ষা আর ফলো-আপ RxStatus-এর লাইনে; পুরোনো সার্ভারে আগের মতো এখানে
const details = computed<[string, string][]>(() => [
  ['medication', meds.value.slice(0, 3).map((m) => m.name).join(', ') + (meds.value.length > 3 ? ' +' + num(meds.value.length - 3) : '')],
  ['list_alt', !props.rx.state && props.rx.tests?.length ? tr(`${props.rx.tests.length} test(s)`, `${num(props.rx.tests.length)}টা পরীক্ষা`) : ''],
  ['calendar_month', !props.rx.state && props.rx.followUpDate ? tr('Come again: ', 'আবার দেখাবেন: ') + formatDate(props.rx.followUpDate) : ''],
]);
</script>

<template>
  <DirectoryCard
    :class="['rx-card', cancelled && 'rx-cancelled']"
    :title="rx.doctor?.name || '-'"
    :title-tag="practitionerLabel({ ...rx.doctor, type: rx.doctorType ?? rx.doctor?.type }) ?? (isNewType(rx.doctorType ?? rx.doctor?.type) ? newTypeLabel((rx.doctorType ?? rx.doctor?.type)!) : systemTag(rx.doctorType ?? rx.doctor?.type, rx.doctor?.system))"
    icon="medication"
    :subtitle="rx.doctor?.degrees || null"
    :status="cancelled ? { text: tr('Cancelled', 'বাতিল'), good: false } : null"
    :meta="[formatDate(rx.createdAt), patientLine(rx)].filter(Boolean).join(' · ')"
    :details="details"
    :selected="selected"
    :on-click="onClick"
  >
    <template #extra><RxStatus :rx="rx" /></template>
  </DirectoryCard>
</template>

<style scoped>
/* ডাক্তার বাতিল করলে কার্ড ধূসর (লেখা পড়া যায় — দুই থিমেই) */
.rx-cancelled { background: var(--surface-low); color: var(--on-surface-variant); }
.rx-cancelled :deep(.t-title-m), .rx-cancelled :deep(.c-primary) { color: var(--on-surface-variant); }
.rx-cancelled :deep(.avatar) { filter: grayscale(1); opacity: 0.7; }
</style>
