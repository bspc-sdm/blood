<script setup lang="ts">
// একজন রোগীর আগের প্রেসক্রিপশন
import { Button, EmptyState, ErrorBox, IconButton, SkeletonCards } from '@/ui/kit';
import { errorText } from '@/lib/api';
import { useCall } from '@/lib/query';
import { tr } from '@/lib/i18n';
import { formatDate } from '@/lib/format';
import { useNav } from '@/lib/nav';
import { ageText, genderLabel, targetBody, type DoctorLab, type DoctorRx, type RxTarget } from './model';
import type { Link } from './patients';
import RxCard from './RxCard.vue';
import DoctorLabList from './DoctorLabList.vue';

const props = defineProps<{ target: RxTarget; link: Link; wide: boolean }>();
const emit = defineEmits<{ close: []; write: [] }>();
const nav = useNav();
const q = useCall<{ items: DoctorRx[]; labOrders?: DoctorLab[] }>('rx_patient_history', targetBody(props.target));
// এই প্রেসক্রিপশনের পার্টনার ল্যাবের পরীক্ষা — ফল শুধু রিপোর্ট দেখার টাকা মিললে / ফি না থাকলে
const labsOf = (id: string) => (q.data?.labOrders ?? []).filter((o) => o.rxId === id);
</script>

<template>
  <div class="col gap-12">
    <div class="row">
      <IconButton v-if="!wide" icon="arrow_back" :label="tr('Back', 'পেছনে')" @click="emit('close')" />
      <div class="col gap-2 grow">
        <h2 class="t-title-l">{{ target.name || tr('Patient', 'রোগী') }}</h2>
        <span v-if="target.age != null || target.gender" class="t-body-s muted">{{ [ageText(target.age), genderLabel(target.gender)].filter(Boolean).join(' · ') }}</span>
      </div>
      <IconButton v-if="wide" icon="close" :label="tr('Close', 'বন্ধ')" @click="emit('close')" />
    </div>
    <Button square icon="edit_note" @click="emit('write')">{{ tr('Write a prescription', 'প্রেসক্রিপশন লিখুন') }}</Button>
    <SkeletonCards v-if="q.isPending" :n="2" :h="110" />
    <ErrorBox v-else-if="q.isError" :error="errorText(q.error)" :on-retry="() => q.refetch()" />
    <EmptyState v-else-if="!q.data?.items.length" icon="medication" :title="tr('No prescription for this patient yet', 'এই রোগীকে এখনো প্রেসক্রিপশন দেননি')" />
    <template v-else>
      <h3 class="rxd-group-title">{{ tr('Earlier prescriptions', 'আগের প্রেসক্রিপশন') }}</h3>
      <div v-for="rx in q.data.items" :key="rx.id" class="col gap-6">
        <RxCard :rx="rx" :on-click="() => nav(link('/view/' + rx.id))" :title="formatDate(rx.createdAt)" />
        <section v-if="labsOf(rx.id).length" class="card col gap-6 rxd-labs-card" :aria-label="tr('Test reports', 'পরীক্ষার রিপোর্ট')">
          <span class="t-label-m muted">{{ tr('Test reports', 'পরীক্ষার রিপোর্ট') }}</span>
          <DoctorLabList :labs="labsOf(rx.id)" />
        </section>
      </div>
    </template>
  </div>
</template>
