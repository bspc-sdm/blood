<script setup lang="ts">
defineProps<{ text?: string | null }>();
</script>

<template>
  <p v-if="text" class="info-text" role="status">{{ text }}</p>
</template>
