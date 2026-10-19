<script setup lang="ts">
// ফার্মেসি — "পণ্য ও স্টক": সরকারি ওষুধের তালিকা থেকে বা নিজে লিখে পণ্য; পরিমাণ, কেনা দাম, বিক্রি দাম, ছাড়, মেয়াদ — সব ঐচ্ছিক।
// শুধু নাম রাখলেও আগের "স্টকের তালিকা"-র মতো চলে (রোগীরা দেখেন কোন দোকানে আছে)। অ্যাপের PharmacyBooks.kt-এর ItemsPane।
import { computed, ref } from 'vue';
import { ChoiceChips, ErrorText, Icon, Loading } from '@/ui/kit';
import { errorText } from '@/lib/api';
import { useCall } from '@/lib/query';
import { num, tr } from '@/lib/i18n';
import Panel from '../Panel.vue';
import { useBooksRefresh, type ShopItem } from './model';
import type { Draft, Filter } from './types';
import AddPanel from './AddPanel.vue';
import BulkPanel from './BulkPanel.vue';
import ItemList from './ItemList.vue';
import ItemDialog from './ItemDialog.vue';
import AdjustDialog from './AdjustDialog.vue';

const q = useCall<{ items: ShopItem[] }>('books_items', {});
const items = computed(() => q.data?.items);
const filter = ref<Filter>('all');
const find = ref('');
const editing = ref<Draft | null>(null);
const adjusting = ref<ShopItem | null>(null);
const refresh = useBooksRefresh();
const have = computed(() => new Set((items.value ?? []).map((i) => i.name.trim().toLowerCase())));
const shown = computed(() => {
  const t = find.value.trim().toLowerCase();
  const f = filter.value;
  return (items.value ?? []).filter(
    (i) =>
      (f === 'all' || (f === 'low' && i.low) || (f === 'exp' && i.expDays != null && i.expDays <= 60) || (f === 'out' && !i.inStock)) &&
      (!t || i.name.toLowerCase().includes(t) || i.generic.toLowerCase().includes(t)),
  );
});
const count = (f: Filter) => (items.value ?? []).filter((i) => (f === 'low' ? i.low : f === 'exp' ? i.expDays != null && i.expDays <= 60 : !i.inStock)).length;
function saved() {
  editing.value = null;
  refresh();
}
function toAdjust(i: ShopItem) {
  editing.value = null;
  adjusting.value = i;
}
function adjusted() {
  adjusting.value = null;
  refresh();
}
</script>

<template>
  <div class="col gap-16">
    <div class="rxp-two bk-add-row">
      <AddPanel :have="have" :on-added="refresh" @pick="(d) => (editing = d)" />
      <BulkPanel :on-added="refresh" />
    </div>
    <Panel :title="tr('Products & stock', 'পণ্য ও স্টক')" icon="list_alt" :count="items ? tr(`${items.length} products`, `${num(items.length)}টা পণ্য`) : undefined">
      <div class="row wrap gap-8">
        <div class="rxp-search rxp-search-sm">
          <Icon name="search" />
          <input v-model="find" class="input" :placeholder="tr('Find in my list', 'তালিকায় খুঁজুন')" :aria-label="tr('Find in my list', 'তালিকায় খুঁজুন')" />
        </div>
        <ChoiceChips
          :model-value="filter"
          :options="[
            { value: 'low', label: tr('Low stock', 'কম স্টক') + (items ? ` (${num(count('low'))})` : '') },
            { value: 'exp', label: tr('Expiring', 'মেয়াদ শেষের পথে') + (items ? ` (${num(count('exp'))})` : '') },
            { value: 'out', label: tr('Out of stock', 'স্টকে নেই') + (items ? ` (${num(count('out'))})` : '') },
          ]"
          @update:model-value="(v: Filter) => (filter = v === filter ? 'all' : v)"
        />
      </div>
      <template v-if="!items">
        <ErrorText v-if="q.error" :text="errorText(q.error)" />
        <Loading v-else />
      </template>
      <ItemList v-else :items="shown" :total="items.length" :on-changed="refresh" @edit="(i) => (editing = i)" @adjust="(i) => (adjusting = i)" />
    </Panel>
    <ItemDialog v-if="editing" :key="editing.id ?? editing.name" :start="editing" @close="editing = null" @saved="saved" @adjust="toAdjust" />
    <AdjustDialog v-if="adjusting" :item="adjusting" @close="adjusting = null" @done="adjusted" />
  </div>
</template>
