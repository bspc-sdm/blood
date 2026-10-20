<script setup lang="ts">
// অ্যাপের SmallAction: ছোট গোল কাজের বাটন
import Icon from './Icon.vue';

defineProps<{ icon?: string; primary?: boolean; busy?: boolean; disabled?: boolean }>();
</script>

<template>
  <button type="button" :disabled="disabled || busy" :class="['act', primary && 'primary']">
    <span v-if="busy" class="spinner small" />
    <Icon v-else-if="icon" :name="icon" />
    <span class="ellipsis"><slot /></span>
  </button>
</template>
