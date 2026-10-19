<script setup lang="ts">
import { Icon } from '@/ui/kit';

defineProps<{ icon: string; t: string; sub?: string | null }>();
</script>

<template>
  <div class="row gap-12">
    <Icon :name="icon" class="amb-head-ic" />
    <div class="col gap-2 grow">
      <h2 class="t-title-l">{{ t }}</h2>
      <span v-if="sub" class="t-body-m muted">{{ sub }}</span>
    </div>
  </div>
</template>
