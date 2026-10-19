<script setup lang="ts">
import { cx } from '@/ui/kit';

defineProps<{ label: string; cls: string }>();
</script>

<template>
  <label :class="cx('rxd-mf', cls)">
    <span class="rxd-mlabel">{{ label }}</span>
    <slot />
  </label>
</template>
