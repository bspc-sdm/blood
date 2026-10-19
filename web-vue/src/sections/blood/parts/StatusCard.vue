<script setup lang="ts">
// রঙিন ছোট বার্তার কার্ড (আইকন + মোটা লেখা)
import { Icon } from '@/ui/kit';

defineProps<{ icon: string; tone?: 'good' | 'high' }>();
</script>

<template>
  <div :class="['card blood-status', tone === 'good' ? 'good' : 'high']">
    <Icon :name="icon" :size="24" class="c-primary none" />
    <span class="semi"><slot /></span>
  </div>
</template>
