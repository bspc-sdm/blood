<script setup lang="ts">
import { computed } from 'vue';
import { tr } from '@/lib/i18n';

const PRINT_PATH = 'M19 8H5c-1.66 0-3 1.34-3 3v6h4v4h12v-4h4v-6c0-1.66-1.34-3-3-3zm-3 11H8v-5h8v5zm3-7c-.55 0-1-.45-1-1s.45-1 1-1 1 .45 1 1-.45 1-1 1zm-1-9H6v4h12V3z';
const label = computed(() => tr('Print', 'প্রিন্ট করুন'));
const print = () => window.print();
</script>

<template>
  <button type="button" class="icon-btn" :aria-label="label" :title="label" @click="print">
    <svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true" focusable="false"><path :d="PRINT_PATH" /></svg>
  </button>
</template>
