<script setup lang="ts">
// "রক্ত দিতে চাই" — যিনি এখনো ডোনারের তথ্য দেননি
import Icon from '@/ui/Icon.vue';
import { tr } from '@/lib/i18n';

const emit = defineEmits<{ click: [] }>();
</script>

<template>
  <button type="button" class="acct-donor-cta" @click="emit('click')">
    <Icon name="volunteer_activism" :size="22" />
    <span class="grow t-title-s">{{ tr('I want to donate blood', 'রক্ত দিতে চাই') }}</span>
    <Icon name="chevron_right" :size="20" />
  </button>
</template>
