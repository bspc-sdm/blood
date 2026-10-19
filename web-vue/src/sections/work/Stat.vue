<script setup lang="ts">
import { Icon } from '@/ui/kit';

defineProps<{ value: string | number; label: string; icon?: string }>();
</script>

<template>
  <div class="stat work-stat">
    <Icon v-if="icon" :name="icon" class="work-stat-ic" />
    <span class="v"><slot name="value">{{ value }}</slot></span>
    <span class="l">{{ label }}</span>
  </div>
</template>
