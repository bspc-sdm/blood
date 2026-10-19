<script setup lang="ts">
import { tr } from '@/lib/i18n';
import Tag from './Tag.vue';
</script>

<template>
  <Tag tone="error">{{ tr('Urgent', 'জরুরি') }}</Tag>
</template>
