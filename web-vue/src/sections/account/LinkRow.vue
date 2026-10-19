<script setup lang="ts">
// নতুন ট্যাবে খোলে এমন সারি (প্রাইভেসি পলিসি, শর্ত)
import Icon from '@/ui/Icon.vue';

defineProps<{ icon: string; title: string; sub?: string; href: string }>();
</script>

<template>
  <a class="list-row" :href="href" target="_blank" rel="noopener noreferrer">
    <Icon :name="icon" />
    <span class="col gap-2 grow">
      <span class="t-body-l">{{ title }}</span>
      <span v-if="sub" class="sub">{{ sub }}</span>
    </span>
    <Icon name="chevron_right" class="chev" />
  </a>
</template>
