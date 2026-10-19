<script setup lang="ts">
defineProps<{ value: string | number; label: string }>();
</script>

<template>
  <div class="stat">
    <span class="v">{{ value }}</span>
    <span class="l">{{ label }}</span>
  </div>
</template>
