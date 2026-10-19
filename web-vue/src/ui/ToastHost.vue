<script setup lang="ts">
import { toastMessage } from './toast';
</script>

<template>
  <div v-if="toastMessage" class="toast" role="status" aria-live="polite">{{ toastMessage }}</div>
</template>
