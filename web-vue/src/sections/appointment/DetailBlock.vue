<script setup lang="ts">
// বিস্তারিতের একটা ভাগ: শিরোনাম আর আইকনসহ লাইন (খালি লাইন বাদ; কিছু না থাকলে কিছুই না)
import { computed, useSlots } from 'vue';
import { Icon, slotHasContent } from '@/ui/kit';

const props = defineProps<{ title: string; lines: [string, string | null | undefined][] }>();
const slots = useSlots();
const shown = computed(() => props.lines.filter((l) => l[1]));
</script>

<template>
  <section v-if="shown.length || slotHasContent(slots.default)" class="col appt-block">
    <h3 class="t-title-s semi">{{ title }}</h3>
    <div v-for="([ic, text], i) in shown" :key="i" class="row top appt-line">
      <Icon :name="ic" />
      <span class="t-body-l pre">{{ text }}</span>
    </div>
    <div v-if="slotHasContent(slots.default)" class="row wrap"><slot /></div>
  </section>
</template>
