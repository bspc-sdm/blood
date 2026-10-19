<script setup lang="ts">
// অ্যাপের RxListCard (ডাক্তারের দিক)
import { computed } from 'vue';
import { DirectoryCard } from '@/ui/kit';
import { num, tr } from '@/lib/i18n';
import { formatDate } from '@/lib/format';
import { ageText, genderLabel, type DoctorRx } from './model';

const props = defineProps<{ rx: DoctorRx; onClick: () => void; title?: string }>();
const meds = computed(() => props.rx.medicines ?? []);
</script>

<template>
  <DirectoryCard
    :title="title ?? (rx.patient?.name || '-')"
    icon="medication"
    :subtitle="title ? rx.diagnosis || null : [ageText(rx.patient?.age), genderLabel(rx.patient?.gender)].filter(Boolean).join(' · ') || null"
    :status="rx.status === 'cancelled' ? { text: tr('Cancelled', 'বাতিল'), good: false } : null"
    :meta="title ? null : formatDate(rx.createdAt)"
    :details="[
      ['medication', meds.slice(0, 3).map((m) => m.name).join(', ') + (meds.length > 3 ? ' +' + num(meds.length - 3) : '')],
      ['list_alt', rx.tests?.length ? tr(`${rx.tests.length} test(s)`, `${num(rx.tests.length)}টা পরীক্ষা`) : ''],
      ['calendar_month', rx.followUpDate ? tr('Come again: ', 'আবার দেখাবেন: ') + formatDate(rx.followUpDate) : ''],
    ]"
    :on-click="onClick"
  />
</template>
