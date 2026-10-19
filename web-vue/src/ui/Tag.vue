<script setup lang="ts">
defineProps<{ tone?: 'error' | 'good' | 'info' | 'primary' }>();
</script>

<template>
  <span :class="['tag', tone]"><slot /></span>
</template>
