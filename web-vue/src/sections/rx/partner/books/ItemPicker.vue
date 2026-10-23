<script setup lang="ts">
// দোকানের পণ্য খোঁজা (কেনা/বিক্রির ফর্মে): ↑↓ দিয়ে বাছা, Enter-এ যোগ; তালিকায় না থাকলে নতুন নাম (কেনায়)
import { computed, onMounted, ref } from 'vue';
import { Icon, cx } from '@/ui/kit';
import { tr } from '@/lib/i18n';
import { findItems, money, qtyText, unitText, type ShopItem } from './model';

const props = defineProps<{ items: ShopItem[]; onNew?: (name: string) => void; placeholder?: string; autofocus?: boolean }>();
const emit = defineEmits<{ pick: [i: ShopItem] }>();
const q = ref('');
const sel = ref(0);
const input = ref<HTMLInputElement | null>(null);
const found = computed(() => findItems(props.items, q.value));
onMounted(() => {
  if (props.autofocus) input.value?.focus();
});
const term = computed(() => q.value.trim());
const exact = computed(() => props.items.some((i) => i.name.toLowerCase() === term.value.toLowerCase()));
function pick(i: ShopItem) {
  emit('pick', i);
  q.value = '';
  sel.value = 0;
  input.value?.focus();
}
function addNew() {
  if (!props.onNew || term.value.length < 2 || exact.value) return;
  props.onNew(term.value);
  q.value = '';
}
function onKey(e: KeyboardEvent) {
  if (e.key === 'ArrowDown') {
    e.preventDefault();
    sel.value = Math.min(found.value.length - 1, sel.value + 1);
  } else if (e.key === 'ArrowUp') {
    e.preventDefault();
    sel.value = Math.max(0, sel.value - 1);
  } else if (e.key === 'Enter') {
    e.preventDefault();
    if (found.value[sel.value]) pick(found.value[sel.value]);
    else addNew();
  } else if (e.key === 'Escape') q.value = '';
}
const meta = (i: ShopItem) => [i.qty != null ? tr('Stock ', 'স্টক ') + qtyText(i.qty) + (i.unit ? ` ${unitText(i.unit)}` : '') : '', i.salePrice != null ? money(i.salePrice) : ''].filter(Boolean).join(' · ');
</script>

<template>
  <div class="col gap-6 bk-picker">
    <div class="rxp-search">
      <Icon name="search" />
      <input
        ref="input"
        class="input"
        :value="q"
        maxlength="60"
        role="combobox"
        :aria-expanded="found.length > 0"
        :aria-label="placeholder ?? tr('Find a product', 'পণ্য খুঁজুন')"
        :placeholder="placeholder ?? tr('Find a product', 'পণ্য খুঁজুন')"
        @input="(e) => { q = (e.target as HTMLInputElement).value; sel = 0; }"
        @keydown="onKey"
      />
    </div>
    <ul v-if="term" class="rxp-found" role="listbox">
      <li v-for="(i, k) in found" :key="i.id" role="option" :aria-selected="k === sel" :class="cx('bk-pick', k === sel && 'on')" @mouseenter="sel = k" @click="pick(i)">
        <div class="col gap-2 grow">
          <span class="semi">{{ i.name }}</span>
          <span class="t-body-s muted num">{{ meta(i) }}</span>
        </div>
        <Icon name="add" />
      </li>
      <li v-if="!found.length && !onNew" class="t-body-s muted">{{ tr('No match', 'মেলেনি') }}</li>
      <li v-if="onNew && !exact && term.length >= 2" class="rxp-found-free bk-pick" @click="addNew">
        <span class="grow t-body-s">{{ tr(`New: "${term}"`, `নতুন: "${term}"`) }}</span>
        <Icon name="add" />
      </li>
    </ul>
  </div>
</template>
