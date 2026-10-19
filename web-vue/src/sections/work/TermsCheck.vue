<script setup lang="ts">
import { Check } from '@/ui/kit';
import { tr } from '@/lib/i18n';
import { TERMS_URL } from './shared';

defineProps<{ modelValue: boolean }>();
const emit = defineEmits<{ 'update:modelValue': [v: boolean] }>();
</script>

<template>
  <div class="col gap-2">
    <Check :model-value="modelValue" @update:model-value="(v) => emit('update:modelValue', v)">{{ tr('I accept the terms for service providers', 'সেবাদাতাদের শর্ত মেনে নিচ্ছি') }}</Check>
    <a :href="TERMS_URL" target="_blank" rel="noreferrer" class="t-body-s c-primary work-terms-link">{{ tr('Read the terms', 'শর্তগুলো পড়ুন') }}</a>
  </div>
</template>
