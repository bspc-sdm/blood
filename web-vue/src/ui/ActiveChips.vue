<script setup lang="ts">
// চালু ফিল্টার (মুছে ফেলা যায়) — অ্যাপের InputChip
import { tr } from '@/lib/i18n';
import Icon from './Icon.vue';

defineProps<{ items: { label: string; onRemove: () => void }[] }>();
</script>

<template>
  <div v-if="items.length" class="chips scroll">
    <button v-for="f in items" :key="f.label" type="button" class="chip on" :aria-label="tr('Remove', 'সরান') + ': ' + f.label" @click="f.onRemove()">
      {{ f.label }}<Icon name="close" />
    </button>
  </div>
</template>
