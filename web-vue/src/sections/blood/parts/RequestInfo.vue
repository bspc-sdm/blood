<script setup lang="ts">
// রিকোয়েস্টের পুরো তথ্য (অ্যাপের RequestInfo)
import { computed } from 'vue';
import { BloodGroupBadge, UrgentTag } from '@/ui/kit';
import { num, tr } from '@/lib/i18n';
import { formatKm } from '@/lib/format';
import { districtLabel } from '@/lib/geo';
import { isActive, neededText, type BloodRequest } from '../model';
import InfoLine from './InfoLine.vue';
import ClosedTag from './ClosedTag.vue';

const props = defineProps<{ r: BloodRequest; km?: number | null }>();
const active = computed(() => isActive(props.r));
</script>

<template>
  <section class="card blood-info">
    <div class="row gap-16">
      <BloodGroupBadge :group="r.patientGroup" :size="64" />
      <div class="col gap-4">
        <h3 class="t-title-l">{{ tr(`${num(r.bags)} bag${r.bags > 1 ? 's' : ''} needed`, `${num(r.bags)} ব্যাগ রক্ত দরকার`) }}</h3>
        <UrgentTag v-if="r.urgent && active" />
        <ClosedTag v-if="!active" />
      </div>
    </div>
    <InfoLine icon="local_hospital" :label="tr('Hospital', 'হাসপাতাল')">{{ r.hospital }}</InfoLine>
    <InfoLine icon="location_on" :label="tr('Area', 'এলাকা')">{{ [districtLabel(r.placeName), km != null ? formatKm(km) : null].filter(Boolean).join(' · ') }}</InfoLine>
    <InfoLine icon="calendar_month" :label="tr('Needed by', 'কবে লাগবে')">{{ neededText(r) }}</InfoLine>
    <InfoLine icon="directions_run" :label="tr('Travel cost', 'যাতায়াত খরচ')">{{ r.travelFare ? tr('Will be paid', 'দেওয়া হবে') : tr('Not provided', 'দেওয়া হবে না') }}</InfoLine>
    <InfoLine v-if="r.note.trim()" icon="info" :label="tr('Note', 'অতিরিক্ত তথ্য')">{{ r.note }}</InfoLine>
    <InfoLine v-if="r.requesterName" icon="person_outline" :label="tr('Requested by', 'রিকোয়েস্ট দিয়েছেন')">{{ r.requesterName }}</InfoLine>
  </section>
</template>
