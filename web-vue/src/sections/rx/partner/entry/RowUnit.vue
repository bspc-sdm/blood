<script setup lang="ts">
import { tr } from '@/lib/i18n';
import type { Row } from '../labdetail';

defineProps<{ r: Row; set: (id: number, p: Partial<Row>) => void }>();
</script>

<template>
  <span v-if="r.fixed" class="t-body-s muted">{{ r.unit }}</span>
  <input v-else class="input rxp-cell rxp-unit" :value="r.unit" maxlength="30" :aria-label="tr('Unit', 'একক')" :placeholder="tr('Unit', 'একক')" @input="set(r.id, { unit: ($event.target as HTMLInputElement).value })" />
</template>
