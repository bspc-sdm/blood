<script setup lang="ts">
// পাতা ২: চওড়া কাজের পাতা ("আমার কাজ": টেবিল, পরিসংখ্যান)
import { usePageTitle } from './chrome';

const props = defineProps<{ title: string }>();
usePageTitle(() => props.title);
</script>

<template>
  <div class="ws">
    <div class="ws-inner">
      <div v-if="$slots.actions || $slots.intro" class="row wrap" :style="{ justifyContent: 'space-between' }">
        <div class="grow"><slot name="intro" /></div>
        <div v-if="$slots.actions" class="row wrap"><slot name="actions" /></div>
      </div>
      <slot />
    </div>
  </div>
</template>
