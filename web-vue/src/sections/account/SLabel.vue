<script setup lang="ts">
import Icon from '@/ui/Icon.vue';

defineProps<{ icon: string }>();
</script>

<template>
  <h2 class="acct-slabel">
    <Icon :name="icon" :size="24" />
    <span><slot /></span>
  </h2>
</template>
