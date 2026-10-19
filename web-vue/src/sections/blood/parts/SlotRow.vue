<script setup lang="ts">
// বামে আইকন (গ্রুপের গোলের নিচে মাঝ বরাবর), ডানে লেখা
import { Icon } from '@/ui/kit';
import { BADGE_SLOT } from './consts';

withDefaults(defineProps<{ icon?: string; slotW?: number; muted?: boolean }>(), { slotW: BADGE_SLOT });
</script>

<template>
  <div class="row" :style="{ gap: '12px' }">
    <span :class="['blood-slot', muted && 'muted']" :style="{ width: slotW + 'px' }"><Icon v-if="icon" :name="icon" :size="20" /></span>
    <div class="grow"><slot /></div>
  </div>
</template>
