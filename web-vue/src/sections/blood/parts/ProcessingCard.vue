<script setup lang="ts">
// ডোনার যে রিকোয়েস্টে রাজি হয়েছেন: "প্রসেসিং" কার্ড
import { computed } from 'vue';
import { Action, BloodGroupBadge, Icon, cardKey } from '@/ui/kit';
import { tr } from '@/lib/i18n';
import { deadline, isActive, neededText, type BloodRequest } from '../model';
import Countdown from './Countdown.vue';

const props = defineProps<{ r: BloodRequest; onClick?: () => void; onDonated?: () => void; onNotDonated?: () => void; busy?: boolean; selected?: boolean }>();
const active = computed(() => isActive(props.r));
</script>

<template>
  <article
    :class="['card blood-proc', onClick && 'click', selected && 'sel']"
    :role="onClick ? 'button' : undefined"
    :tabindex="onClick ? 0 : undefined"
    @click="onClick?.()"
    @keydown="cardKey($event, onClick)"
  >
    <div class="row gap-6 c-primary">
      <Icon name="sync" :size="18" />
      <span class="t-label-l bold">{{ tr('Processing', 'প্রসেসিং') }}</span>
    </div>
    <div class="row gap-12">
      <BloodGroupBadge :group="r.patientGroup" :size="40" />
      <div class="col gap-2 grow">
        <span class="semi ellipsis">{{ r.hospital }}</span>
        <span class="t-body-s">{{ neededText(r) }}</span>
      </div>
    </div>
    <Countdown v-if="active" :target="deadline(r)" />
    <template v-else>
      <p class="semi">{{ tr('This request has ended. Did you donate?', 'রিকোয়েস্টটা শেষ হয়েছে। আপনি কি রক্ত দিয়েছেন?') }}</p>
      <div class="row gap-8" @click.stop>
        <Action v-if="onDonated" icon="check_circle" primary :busy="busy" @click="onDonated()">{{ tr('I donated', 'রক্ত দিয়েছি') }}</Action>
        <Action v-if="onNotDonated" icon="close" :disabled="busy" @click="onNotDonated()">{{ tr("I didn't", 'দিইনি') }}</Action>
      </div>
    </template>
  </article>
</template>
