<script setup lang="ts">
// ১–৫ তারা
import { Icon } from '@/ui/kit';
import { num } from '@/lib/i18n';

defineProps<{ value: number; label: string }>();
const emit = defineEmits<{ change: [n: number] }>();
</script>

<template>
  <div class="services-stars" role="radiogroup" :aria-label="label">
    <button v-for="i in 5" :key="i" type="button" role="radio" :aria-checked="value === i" :aria-label="num(i)" @click="emit('change', i)">
      <Icon :name="i <= value ? 'star_fill' : 'star'" />
    </button>
  </div>
</template>
