<script setup lang="ts">
// এলাকা বার (অ্যাপের FilterBar)
import { ref } from 'vue';
import { tr } from '@/lib/i18n';
import Icon from './Icon.vue';
import IconButton from './IconButton.vue';

const props = withDefaults(defineProps<{
  areaLabel: string; onArea: () => void; filterCount?: number; onFilter?: () => void;
  search?: string; onSearch?: (s: string) => void; searchHint?: string;
}>(), { filterCount: 0 });
const open = ref(!!props.search);
function toggle() {
  if (open.value) props.onSearch?.('');
  open.value = !open.value;
}
</script>

<template>
  <div class="col gap-6">
    <div class="fbar">
      <button type="button" class="area-btn" :aria-label="tr('Change area', 'এলাকা বদলান') + ': ' + areaLabel" @click="onArea()">
        <Icon name="location_on" />
        <span>{{ areaLabel || tr('Choose area', 'এলাকা বেছে নিন') }}</span>
        <Icon name="expand_more_round" />
      </button>
      <IconButton v-if="onFilter" icon="tune" :label="tr('Filter', 'ফিল্টার')" :badge="filterCount || null" :style="filterCount ? { color: 'var(--primary)' } : undefined" @click="onFilter()" />
      <IconButton v-if="onSearch" :icon="open ? 'close' : 'search'" :label="open ? tr('Close search', 'খোঁজা বন্ধ') : searchHint ?? tr('Search by name', 'নাম দিয়ে খুঁজুন')" @click="toggle" />
    </div>
    <input
      v-if="onSearch && open"
      v-focus
      class="input"
      :value="search ?? ''"
      :placeholder="searchHint ?? tr('Search by name', 'নাম দিয়ে খুঁজুন')"
      @input="onSearch(($event.target as HTMLInputElement).value)"
    />
    <slot />
  </div>
</template>
