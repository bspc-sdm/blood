<script setup lang="ts">
import { computed } from 'vue';
import { Button, IconLine, PhoneLine, Tag } from '@/ui/kit';
import { formatDate } from '@/lib/format';
import { num, tr } from '@/lib/i18n';
import { apptChamber, apptWhen, badgeLabel, isActive, statusLabel, statusTone, type Appointment } from './model';
import { usePatientQueue } from './queue';
import PatientQueue from './PatientQueue.vue';

const props = defineProps<{ a: Appointment }>();
const emit = defineEmits<{ cancel: [] }>();
// আজকের সিরিয়াল চললে নম্বর নিচের সারির কার্ডেই (দুবার নয়)
const pq = usePatientQueue(() => props.a);
const live = computed(() => pq.data?.live === true);
</script>

<template>
  <div class="row">
    <Tag :tone="statusTone(a.status)">{{ statusLabel(a.status) }}</Tag>
    <span class="t-body-s muted grow">{{ badgeLabel(a.type) }}</span>
  </div>
  <div v-if="a.serial > 0 && !live" class="card tonal appt-serial">
    <span class="t-label-m">{{ tr('Serial #', 'সিরিয়াল নং ').trim() }}</span>
    <span class="appt-serial-n num">{{ num(a.serial) }}</span>
  </div>
  <PatientQueue :a="a" />
  <div class="col">
    <IconLine icon="calendar_month">{{ apptWhen(a, formatDate) }}</IconLine>
    <IconLine v-if="a.chamber" icon="local_hospital">{{ apptChamber(a) + (a.room ? ' · ' + a.room : '') }}</IconLine>
    <IconLine v-if="a.schedule" icon="schedule">{{ a.schedule }}</IconLine>
  </div>
  <div v-if="a.note" class="col gap-4">
    <span class="t-label-m muted">{{ tr('Reason for visit', 'কী সমস্যা') }}</span>
    <p class="t-body-l pre">{{ a.note }}</p>
  </div>
  <p v-if="a.providerNote" class="t-body-m appt-note">{{ tr('Note: ', 'নোট: ') + a.providerNote }}</p>
  <PhoneLine v-if="a.otherPhone" :phone="a.otherPhone" big />
  <div v-if="isActive(a.status)" class="row">
    <Button variant="danger-text" icon="close" @click="emit('cancel')">{{ tr('Cancel appointment', 'অ্যাপয়েন্টমেন্ট বাতিল') }}</Button>
  </div>
</template>
