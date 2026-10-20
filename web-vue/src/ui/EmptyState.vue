<script setup lang="ts">
import Icon from './Icon.vue';

defineProps<{ icon: string; title: string; text?: string }>();
</script>

<template>
  <div class="empty">
    <Icon :name="icon" />
    <div class="t">{{ title }}</div>
    <div v-if="text" class="s">{{ text }}</div>
    <slot />
  </div>
</template>
