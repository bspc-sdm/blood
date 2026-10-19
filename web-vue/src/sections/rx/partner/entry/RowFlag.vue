<script setup lang="ts">
import { Tag } from '@/ui/kit';
import { tr } from '@/lib/i18n';
import { flagText } from '../model';
import { flagOf, type Row } from '../labdetail';

defineProps<{ r: Row; set: (id: number, p: Partial<Row>) => void }>();
</script>

<template>
  <template v-if="!r.long">
    <template v-if="r.numeric">
      <Tag v-if="flagOf(r)" tone="error">{{ flagText(flagOf(r)) }}</Tag>
      <span v-else class="muted">—</span>
    </template>
    <select v-else class="select rxp-cell rxp-flag" :value="r.flag" :aria-label="`${r.test}: ${tr('High/low', 'বেশি/কম')}`" @change="set(r.id, { flag: ($event.target as HTMLSelectElement).value })">
      <option value="">—</option>
      <option value="high">{{ flagText('high') }}</option>
      <option value="low">{{ flagText('low') }}</option>
      <option value="abnormal">{{ flagText('abnormal') }}</option>
    </select>
  </template>
</template>
