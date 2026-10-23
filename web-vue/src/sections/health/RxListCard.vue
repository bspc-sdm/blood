<script setup lang="ts">
import { computed } from 'vue';
import { DirectoryCard } from '@/ui/kit';
import { num, tr } from '@/lib/i18n';
import { formatDate } from '@/lib/format';
import { patientLine, type Rx } from './rx';
import { isNewType, newTypeLabel, systemTag } from '@/lib/practice';

const props = defineProps<{ rx: Rx; selected: boolean; onClick: () => void }>();
const meds = computed(() => props.rx.medicines ?? []);
const details = computed<[string, string][]>(() => [
  ['medication', meds.value.slice(0, 3).map((m) => m.name).join(', ') + (meds.value.length > 3 ? ' +' + num(meds.value.length - 3) : '')],
  ['list_alt', props.rx.tests?.length ? tr(`${props.rx.tests.length} test(s)`, `${num(props.rx.tests.length)}টা পরীক্ষা`) : ''],
  ['calendar_month', props.rx.followUpDate ? tr('Come again: ', 'আবার দেখাবেন: ') + formatDate(props.rx.followUpDate) : ''],
]);
</script>

<template>
  <DirectoryCard
    :title="rx.doctor?.name || '-'"
    :title-tag="isNewType(rx.doctorType ?? rx.doctor?.type) ? newTypeLabel((rx.doctorType ?? rx.doctor?.type)!) : systemTag(rx.doctorType ?? rx.doctor?.type, rx.doctor?.system)"
    icon="medication"
    :subtitle="rx.doctor?.degrees || null"
    :status="rx.status === 'cancelled' ? { text: tr('Cancelled', 'বাতিল'), good: false } : null"
    :meta="[formatDate(rx.createdAt), patientLine(rx)].filter(Boolean).join(' · ')"
    :details="details"
    :selected="selected"
    :on-click="onClick"
  />
</template>
