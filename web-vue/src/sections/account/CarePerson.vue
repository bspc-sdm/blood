<script setup lang="ts">
import { Avatar } from '@/ui/kit';

defineProps<{ icon: string; name: string; sub?: string }>();
</script>

<template>
  <div class="acct-person">
    <hr class="divider" />
    <div class="row gap-12">
      <Avatar :icon="icon" :size="40" />
      <div class="col gap-2 grow">
        <span class="t-body-l semi ellipsis">{{ name }}</span>
        <span v-if="sub" class="t-body-s muted acct-wrap">{{ sub }}</span>
      </div>
      <slot name="tag" />
    </div>
    <div v-if="$slots.default" class="row wrap gap-8"><slot /></div>
  </div>
</template>
