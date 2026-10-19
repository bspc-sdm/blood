<script setup lang="ts">
import { computed } from 'vue';
import { BloodBag, BloodGroupBadge, IconLine, UrgentTag, cardKey } from '@/ui/kit';
import { num, tr } from '@/lib/i18n';
import { formatKm } from '@/lib/format';
import { districtLabel } from '@/lib/geo';
import { bagsLeft, isActive, neededText, type BloodRequest } from '../model';
import DonorStatusTag from './DonorStatusTag.vue';
import ClosedTag from './ClosedTag.vue';

const props = defineProps<{ r: BloodRequest; km?: number | null; onClick: () => void; selected?: boolean }>();
const active = computed(() => isActive(props.r));
const left = computed(() => bagsLeft(props.r));
const partial = computed(() => active.value && left.value >= 1 && left.value < props.r.bags);
const shown = computed(() => (partial.value ? left.value : props.r.bags));
</script>

<template>
  <article :class="['card click blood-rc', selected && 'sel']" :aria-pressed="selected" role="button" tabindex="0" @click="onClick()" @keydown="cardKey($event, onClick)">
    <BloodGroupBadge :group="r.patientGroup" />
    <div class="col gap-4 grow">
      <div class="row gap-8">
        <span class="t-title-m ellipsis">{{ r.hospital }}</span>
        <UrgentTag v-if="r.urgent && active" />
      </div>
      <IconLine icon="location_on">{{ [districtLabel(r.placeName), km != null ? formatKm(km) : null].filter(Boolean).join(' · ') }}</IconLine>
      <IconLine icon="schedule">{{ neededText(r) }}</IconLine>
      <IconLine v-if="r.travelFare" icon="directions_run">{{ tr('Travel cost paid', 'যাতায়াত খরচ দেওয়া হবে') }}</IconLine>
      <DonorStatusTag v-if="active" :r="r" />
    </div>
    <div class="blood-bagcol">
      <BloodBag
        :n="shown"
        :filled="active"
        :label="partial ? tr(`${left} of ${r.bags} bag(s) still needed`, `${num(r.bags)} ব্যাগের ${num(left)}টা এখনো দরকার`) : tr(`${r.bags} bag(s) needed`, `${num(r.bags)} ব্যাগ দরকার`)"
      />
      <span v-if="partial" class="t-label-s c-error">{{ tr('left', 'বাকি') }}</span>
      <ClosedTag v-if="!active" />
    </div>
  </article>
</template>
