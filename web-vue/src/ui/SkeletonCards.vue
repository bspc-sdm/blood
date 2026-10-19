<script setup lang="ts">
withDefaults(defineProps<{ n?: number; h?: number }>(), { n: 3, h: 112 });
</script>

<template>
  <div v-for="i in n" :key="i" class="skeleton" :style="{ height: h + 'px' }" />
</template>
