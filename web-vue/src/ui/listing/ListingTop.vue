<script setup lang="ts">
// তালিকার উপরের ছোট অংশ (পাঁচ তালিকায় একই): খোঁজার ঘর + এলাকার চিপ; নিচে এক লাইনে সাজানোর চিপ (ছোট মেনু) আর ছাঁকনির চিপ
// (পাশে সরানো যায়; শেষে ঝাপসা হয়ে যায় যাতে বোঝা যায় আরও আছে; চালু থাকলে "মুছুন")।
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { Icon } from '@/ui/kit';
import { tr } from '@/lib/i18n';
import { LT, type ListingKind, type SortKey } from './model';

export interface FilterChip { key: string; label: string; on: boolean; toggle: () => void; icon?: string }
const props = defineProps<{
  kind: ListingKind;
  search: string;
  areaLabel: string;
  onArea: () => void;
  sort: SortKey | null;
  sorts: SortKey[];
  chips: FilterChip[];
  onClear?: () => void;
  /** শেষে "আরও" (সব ছাঁকনির শিট) — কয়টা চালু */
  onMore?: () => void;
  moreCount?: number;
}>();
const emit = defineEmits<{ 'update:search': [v: string]; sort: [k: SortKey] }>();
const anyOn = computed(() => props.chips.some((c) => c.on) || (props.moreCount ?? 0) > 0);

// সাজানোর মেনু
const sortOpen = ref(false);
const sortRoot = ref<HTMLElement | null>(null);
const onDown = (e: Event) => {
  if (!sortRoot.value?.contains(e.target as Node)) sortOpen.value = false;
};
const onKey = (e: KeyboardEvent) => {
  if (e.key === 'Escape') sortOpen.value = false;
};
const off = () => {
  document.removeEventListener('pointerdown', onDown);
  document.removeEventListener('keydown', onKey);
};
watch(sortOpen, async (o) => {
  off();
  if (!o) return;
  document.addEventListener('pointerdown', onDown);
  document.addEventListener('keydown', onKey);
  await nextTick();
  sortRoot.value?.querySelector<HTMLElement>('[aria-checked="true"]')?.focus();
});
function pick(k: SortKey) {
  sortOpen.value = false;
  emit('sort', k);
}

// চিপের সারি: ডানে আরও থাকলে ঝাপসা
const row = ref<HTMLElement | null>(null);
const fade = ref(false);
const measure = () => {
  const r = row.value;
  fade.value = !!r && r.scrollWidth - r.clientWidth - r.scrollLeft > 2;
};
let ro: ResizeObserver | null = null;
onMounted(() => {
  measure();
  if (typeof ResizeObserver !== 'undefined' && row.value) {
    ro = new ResizeObserver(measure);
    ro.observe(row.value);
  }
});
watch(() => props.chips.map((c) => c.label + c.on).join('|'), () => nextTick(measure));
onBeforeUnmount(() => {
  off();
  ro?.disconnect();
});
</script>

<template>
  <div class="lst-top" data-testid="listing-top">
    <div class="lst-search-row">
      <label class="lst-search">
        <Icon name="search" />
        <input
          type="search"
          class="input"
          :value="search"
          :placeholder="LT.search(kind)"
          :aria-label="LT.search(kind)"
          data-testid="listing-search"
          @input="emit('update:search', ($event.target as HTMLInputElement).value)"
        />
      </label>
      <button type="button" class="chip lead lst-area" :aria-label="LT.changeArea() + ': ' + areaLabel" data-testid="area-chip" @click="onArea()">
        <Icon name="location_on" /><span class="lst-area-t">{{ areaLabel || tr('Choose area', 'এলাকা বেছে নিন') }}</span><Icon name="expand_more_round" />
      </button>
    </div>
    <div class="lst-filter-row">
      <div v-if="sorts.length" ref="sortRoot" class="lst-sort">
        <button
          type="button"
          class="chip lead"
          aria-haspopup="menu"
          :aria-expanded="sortOpen"
          :aria-label="LT.sortLabel() + ': ' + (sort ? LT.sort(sort) : '')"
          data-testid="sort-chip"
          @click="sortOpen = !sortOpen"
        >
          <Icon name="tune" /><span>{{ sort ? LT.sort(sort) : LT.sortLabel() }}</span><Icon name="expand_more_round" />
        </button>
        <div v-if="sortOpen" class="services-menu lst-sort-menu" role="menu" data-testid="sort-menu">
          <button v-for="k in sorts" :key="k" type="button" role="menuitemradio" :aria-checked="k === sort" @click="pick(k)">
            <Icon :name="k === sort ? 'check_circle' : 'schedule'" :class="k !== sort && 'lst-ghost'" />{{ LT.sort(k) }}
          </button>
        </div>
      </div>
      <div :class="['lst-chips-wrap', fade && 'fade']">
        <div ref="row" class="chips scroll lst-chips" role="group" :aria-label="tr('Filters', 'ছাঁকনি')" data-testid="filter-chips" @scroll.passive="measure">
          <button v-if="anyOn && onClear" type="button" class="chip lst-clear" data-testid="filter-clear" @click="onClear()"><Icon name="close" />{{ LT.clear() }}</button>
          <button v-for="c in chips" :key="c.key" type="button" class="chip" :aria-pressed="c.on" :data-chip="c.key" @click="c.toggle()">
            <Icon v-if="c.on" name="check_circle" /><Icon v-else-if="c.icon" :name="c.icon" />{{ c.label }}
          </button>
          <button v-if="onMore" type="button" class="chip" :aria-pressed="(moreCount ?? 0) > 0" data-testid="filter-more" @click="onMore()">
            <Icon name="tune" />{{ LT.more() + ((moreCount ?? 0) > 0 ? ` (${moreCount})` : '') }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>
