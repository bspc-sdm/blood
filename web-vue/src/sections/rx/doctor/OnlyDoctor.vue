<script setup lang="ts">
// সহকারী এই পাতা খুলতে পারেন না (সার্ভারও দেয় না)
import { RouterLink } from 'vue-router';
import { EmptyState } from '@/ui/kit';
import { tr } from '@/lib/i18n';
import type { Link } from './patients';

defineProps<{ link: Link }>();
</script>

<template>
  <EmptyState icon="stethoscope" :title="tr('Only the doctor can do this', 'শুধু ডাক্তার নিজে এটা করতে পারেন')">
    <RouterLink class="btn tonal" :to="link('')">{{ tr('Patients', 'রোগী') }}</RouterLink>
  </EmptyState>
</template>
