<script setup lang="ts">
// নিচের কোণে ভাসমান ছোট বাটন (অ্যাপের SmallFab)
import Icon from './Icon.vue';

defineProps<{ icon: string }>();
</script>

<template>
  <button type="button" class="fab">
    <Icon :name="icon" />
    <slot />
  </button>
</template>
