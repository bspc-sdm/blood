<script setup lang="ts">
import { ref } from 'vue';
import { toast } from '@/ui/kit';
import { call, errorText } from '@/lib/api';
import { tr } from '@/lib/i18n';
import type { ShopItem } from './model';

const props = defineProps<{ i: ShopItem; onChanged: () => void }>();
const busy = ref(false);
async function change(e: Event) {
  const el = e.target as HTMLInputElement;
  busy.value = true;
  try {
    await call('books_item_stock', { id: props.i.id, on: el.checked });
    props.onChanged();
  } catch (er) {
    toast(errorText(er));
    el.checked = props.i.inStock;
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <!-- সুইচের পাশে দেখা যায় এমন লেখা (আমার কাজ CONTRACT §২) -->
  <label class="row gap-6 books-stock-sw">
    <input type="checkbox" role="switch" class="switch" :checked="i.inStock" :disabled="busy" :aria-label="`${i.name}: ${tr('In stock', 'স্টকে আছে')}`" @change="change" />
    <span class="t-body-s">{{ i.inStock ? tr('In stock', 'স্টকে আছে') : tr('Out of stock', 'স্টকে নেই') }}</span>
  </label>
</template>
