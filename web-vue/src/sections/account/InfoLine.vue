<script setup lang="ts">
import Icon from '@/ui/Icon.vue';

defineProps<{ icon: string; label: string; value: string }>();
</script>

<template>
  <div class="acct-info">
    <Icon :name="icon" :size="20" />
    <div class="col gap-2 grow">
      <span class="t-label-m muted">{{ label }}</span>
      <span class="t-body-l">{{ value || '—' }}</span>
    </div>
    <slot name="trailing" />
  </div>
</template>
