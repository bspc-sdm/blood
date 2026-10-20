<script setup lang="ts">
// অ্যাপের আইকন (Android vector থেকে বানানো) — <Icon name="call" />
import { computed } from 'vue';
import { ICONS } from '@/icons/paths';

const props = defineProps<{ name: string; size?: number; title?: string }>();
const ic = computed(() => ICONS[props.name]);
</script>

<template>
  <svg
    v-if="ic"
    :viewBox="ic[0]"
    :width="size"
    :height="size"
    fill="currentColor"
    :aria-hidden="title ? undefined : true"
    :role="title ? 'img' : undefined"
    focusable="false"
  >
    <title v-if="title">{{ title }}</title>
    <template v-for="(p, i) in ic[1]" :key="i">
      <path v-if="typeof p === 'string'" :d="p" />
      <path v-else :d="p[0]" fill-rule="evenodd" />
    </template>
  </svg>
</template>
