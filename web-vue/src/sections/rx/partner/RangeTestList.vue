<script setup lang="ts">
import { Icon, Tag, cx } from '@/ui/kit';
import { isBangla, tr } from '@/lib/i18n';
import Panel from './Panel.vue';
import type { TestDef } from './model';

defineProps<{ shown: TestDef[]; curCode: string | null; saved: Record<string, 'own' | 'reset'>; find: string }>();
const emit = defineEmits<{ find: [v: string]; pick: [code: string] }>();
</script>

<template>
  <Panel :title="tr('Tests', 'পরীক্ষা')" icon="list_alt">
    <div class="rxp-search">
      <Icon name="search" />
      <input class="input" :value="find" :placeholder="tr('Find a test', 'পরীক্ষা খুঁজুন')" :aria-label="tr('Find a test', 'পরীক্ষা খুঁজুন')" @input="emit('find', ($event.target as HTMLInputElement).value)" />
    </div>
    <ul class="rxp-tests" role="listbox" :aria-label="tr('Tests', 'পরীক্ষা')">
      <li v-for="x in shown" :key="x.code">
        <button type="button" role="option" :aria-selected="curCode === x.code" :class="cx(curCode === x.code && 'on')" @click="emit('pick', x.code)">
          <span class="col gap-2 grow">
            <span class="semi">{{ x.name }}</span>
            <span v-if="isBangla() && x.bn" class="t-body-s muted">{{ x.bn }}</span>
          </span>
          <Tag v-if="saved[x.code] === 'own'" tone="good">{{ tr('Saved', 'রাখা আছে') }}</Tag>
          <Icon name="chevron_right" />
        </button>
      </li>
      <li v-if="!shown.length" class="t-body-m muted rxp-pad">{{ tr('No match', 'মেলেনি') }}</li>
    </ul>
  </Panel>
</template>
