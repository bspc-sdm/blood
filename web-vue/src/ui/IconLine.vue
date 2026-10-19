<script setup lang="ts">
import Icon from './Icon.vue';

defineProps<{ icon: string; color?: string }>();
</script>

<template>
  <div class="icon-line" :style="color ? { color } : undefined">
    <Icon :name="icon" />
    <span><slot /></span>
  </div>
</template>
