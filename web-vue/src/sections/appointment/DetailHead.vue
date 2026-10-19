<script setup lang="ts">
// বিস্তারিতের মাথা (অ্যাপের DetailSheet)
import { Avatar } from '@/ui/kit';

defineProps<{ title: string; icon: string; photo?: string | null; subtitle?: string | null; meta?: string | null }>();
</script>

<template>
  <div class="row top gap-16 appt-head">
    <Avatar :photo="photo" :icon="icon" :size="72" />
    <div class="col gap-2 grow">
      <h2 class="t-title-l bold">{{ title }}</h2>
      <span v-if="subtitle" class="t-body-m c-primary">{{ subtitle }}</span>
      <span v-if="meta" class="t-body-m muted">{{ meta }}</span>
      <slot />
    </div>
  </div>
</template>
