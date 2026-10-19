<script setup lang="ts">
defineProps<{ title: string; text: string }>();
</script>

<template>
  <div class="col gap-2">
    <span class="t-label-l health-paper-h">{{ title }}</span>
    <p class="t-body-m pre">{{ text }}</p>
  </div>
</template>
