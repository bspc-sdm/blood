<script setup lang="ts">
import { computed } from 'vue';
import { Icon } from '@/ui/kit';
import { tr } from '@/lib/i18n';
import { useNav } from '@/lib/nav';
import type { AmbReq } from './ambreq';

const props = defineProps<{ r: AmbReq }>();
const nav = useNav();
const title = computed(() => {
  const r = props.r;
  return r.status === 'accepted'
    ? tr(`${r.ambulance?.name ?? 'Ambulance'} is coming`, `${r.ambulance?.name ?? 'অ্যাম্বুলেন্স'} আসছে`)
    : r.status === 'arrived'
      ? tr(`${r.ambulance?.name ?? 'Ambulance'} has arrived`, `${r.ambulance?.name ?? 'অ্যাম্বুলেন্স'} পৌঁছেছে`)
      : tr('Your ambulance request is running', 'আপনার অ্যাম্বুলেন্সের অনুরোধ চলছে');
});
</script>

<template>
  <button type="button" class="amb-banner" @click="nav(`/services/ambulance/call/${r.id}`)">
    <Icon name="ambulance" />
    <span class="col gap-2 grow">
      <span class="t-title-s">{{ title }}</span>
      <span class="t-body-s">{{ tr('Tap to see', 'দেখতে চাপুন') }}</span>
    </span>
    <Icon name="chevron_right" />
  </button>
</template>
