<script setup lang="ts">
// খালি অবস্থা: এক লাইন + একটা কাজ ("এলাকা বদলান"; ছাঁকনিতে না মিললে "মুছুন") — অ্যান্ড্রয়েডের একই লেখা
import { Button, Icon } from '@/ui/kit';
import { LT } from './model';

defineProps<{ icon: string; title: string; onArea?: () => void; onClear?: () => void }>();
</script>

<template>
  <div class="empty lst-empty" data-testid="listing-empty">
    <Icon :name="icon" />
    <div class="t">{{ title }}</div>
    <Button v-if="onClear" variant="tonal" icon="close" @click="onClear()">{{ LT.clear() }}</Button>
    <Button v-else-if="onArea" variant="tonal" icon="location_on" @click="onArea()">{{ LT.changeArea() }}</Button>
  </div>
</template>
