<script setup lang="ts">
// ভুলের লেখা — খালি হলে কিছুই আঁকে না
defineProps<{ text?: string | null }>();
</script>

<template>
  <p v-if="text" class="err-text" role="alert">{{ text }}</p>
</template>
