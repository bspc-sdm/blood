<script setup lang="ts">
withDefaults(defineProps<{ group: string; size?: number }>(), { size: 52 });
</script>

<template>
  <span class="badge-group" :style="{ width: size + 'px', height: size + 'px', fontSize: size * 0.34 + 'px' }">
    {{ group === 'unknown' ? '?' : group }}
  </span>
</template>
