<script setup lang="ts">
import { num } from '@/lib/i18n';
import Icon from './Icon.vue';

defineProps<{ icon: string; label: string; small?: boolean; badge?: number | string | null }>();
</script>

<template>
  <button type="button" :aria-label="label" :title="label" :class="['icon-btn', small && 'small']">
    <Icon :name="icon" />
    <span v-if="badge" class="badge-dot">{{ typeof badge === 'number' ? num(badge > 99 ? '99+' : badge) : badge }}</span>
  </button>
</template>
