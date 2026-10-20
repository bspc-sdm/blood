<script setup lang="ts">
import { IconButton } from '@/ui/kit';
import { tr } from '@/lib/i18n';
import type { Row } from '../labdetail';

defineProps<{ r: Row; set: (id: number, p: Partial<Row>) => void; remove: (id: number) => void }>();
</script>

<template>
  <span v-if="r.fixed" class="semi">{{ r.test }}</span>
  <div v-else class="row gap-4">
    <input class="input rxp-cell" :value="r.test" maxlength="80" :aria-label="tr('Test', 'পরীক্ষা')" :placeholder="tr('Test', 'পরীক্ষা')" @input="set(r.id, { test: ($event.target as HTMLInputElement).value })" />
    <IconButton small icon="close" :label="tr('Remove row', 'সারি সরান')" @click="remove(r.id)" />
  </div>
</template>
