<script setup lang="ts">
// আইকন + ছোট শিরোনাম + মান (অ্যাপের InfoLine)
import { Icon } from '@/ui/kit';

defineProps<{ icon: string; label: string }>();
</script>

<template>
  <div class="row top gap-12">
    <Icon :name="icon" :size="20" class="blood-info-ic" />
    <div class="col gap-2 grow">
      <span class="t-label-m muted">{{ label }}</span>
      <span class="t-body-l pre"><slot /></span>
    </div>
  </div>
</template>
