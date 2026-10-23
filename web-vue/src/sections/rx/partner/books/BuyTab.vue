<script setup lang="ts">
// ফার্মেসি — "মাল কেনা": কোন পণ্য কয়টা, প্রতি একক কেনা দাম, মেয়াদ → পরিমাণ বাড়ে, কেনা দাম গড় হয়। পাশে আগের কেনা।
import { computed, ref } from 'vue';
import { Button, ConfirmDialog, ErrorText, Loading, TextInput, inputValue, toast } from '@/ui/kit';
import { call, errorText } from '@/lib/api';
import { useCall } from '@/lib/query';
import { num, tr } from '@/lib/i18n';
import { dateInputValue, formatDayOf } from '@/lib/format';
import Panel from '../Panel.vue';
import ItemPicker from './ItemPicker.vue';
import { money, numInput, numStr, qtyText, r2, readNum, unitText, useBooksRefresh, type Purchase, type ShopItem } from './model';

interface Line { key: string; itemId: string | null; name: string; unit: string; qty: string; cost: string; expiry: string }
const todayInput = () => dateInputValue(Date.now() + 6 * 3600 * 1000);

const items = useCall<{ items: ShopItem[] }>('books_items', {});
const list = useCall<{ items: Purchase[] }>('books_purchases', {});
const refresh = useBooksRefresh();
const lines = ref<Line[]>([]);
const supplier = ref('');
const date = ref(todayInput());
const busy = ref(false);
const err = ref<string | null>(null);
const deleting = ref<Purchase | null>(null);

function add(i: ShopItem | null, name: string) {
  if (i && lines.value.some((l) => l.itemId === i.id)) return;
  const n = lines.value.length;
  lines.value = [...lines.value, { key: i?.id ?? 'n:' + name, itemId: i?.id ?? null, name: i?.name ?? name, unit: i?.unit ?? '', qty: '', cost: numStr(i?.cost), expiry: '' }];
  setTimeout(() => document.querySelectorAll<HTMLInputElement>('[data-bk-qty]')[n]?.focus(), 30);
}
const set = (k: string, p: Partial<Line>) => (lines.value = lines.value.map((l) => (l.key === k ? { ...l, ...p } : l)));
const parsed = computed(() => lines.value.map((l) => ({ ...l, q: readNum(l.qty), c: readNum(l.cost) })));
const bad = computed(() => parsed.value.some((l) => Number.isNaN(l.q) || Number.isNaN(l.c)));
const ready = computed(() => parsed.value.filter((l) => l.q && l.q > 0));
const total = computed(() => r2(ready.value.reduce((a, l) => a + (l.c != null && !Number.isNaN(l.c) ? l.q! * l.c : 0), 0)));

async function save() {
  if (!ready.value.length || bad.value) return;
  busy.value = true;
  err.value = null;
  try {
    await call('books_purchase_add', {
      at: date.value === todayInput() ? undefined : date.value, supplier: supplier.value,
      lines: ready.value.map((l) => ({ ...(l.itemId ? { itemId: l.itemId } : { name: l.name }), qty: l.q, unitCost: l.c, expiry: l.expiry || null })),
    });
    toast(tr('Saved — stock updated', 'সেভ হয়েছে — স্টক বেড়েছে'));
    lines.value = [];
    supplier.value = '';
    refresh();
  } catch (e) {
    err.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
async function del() {
  try {
    await call('books_purchase_delete', { id: deleting.value!.id });
    refresh();
  } catch (e) {
    toast(errorText(e));
  }
  deleting.value = null;
}
</script>

<template>
  <div class="rxp-grid">
    <div class="col gap-16 rxp-main">
      <Panel :title="tr('Stock in', 'মাল কেনা')" icon="storefront">
        <div class="bk-form-grid">
          <TextInput :label="tr('Date', 'তারিখ')" type="date" :model-value="date" :max="todayInput()" @update:model-value="(v) => (date = v || todayInput())" />
          <TextInput v-model="supplier" :label="tr('Supplier (optional)', 'কার কাছ থেকে (ঐচ্ছিক)')" maxlength="80" />
        </div>
        <Loading v-if="!items.data" />
        <ItemPicker v-else :items="items.data.items" :on-new="(n: string) => add(null, n)" @pick="(i) => add(i, i.name)" />
        <div v-if="lines.length" class="col gap-8">
          <div v-for="l in lines" :key="l.key" class="bk-line">
            <div class="col gap-2 bk-line-name">
              <span class="semi">{{ l.name }}</span>
              <span v-if="!l.itemId" class="t-body-s c-primary">{{ tr('New product', 'নতুন পণ্য') }}</span>
            </div>
            <label class="bk-cell"><span class="t-label-s muted">{{ tr('Qty', 'পরিমাণ') }}{{ l.unit ? ` (${unitText(l.unit)})` : '' }}</span>
              <input data-bk-qty="" class="input num" inputmode="decimal" :value="l.qty" @input="set(l.key, { qty: inputValue($event, numInput) })" />
            </label>
            <label class="bk-cell"><span class="t-label-s muted">{{ tr('Cost / unit', 'কেনা দাম / একক') }}</span>
              <input class="input num" inputmode="decimal" :value="l.cost" @input="set(l.key, { cost: inputValue($event, numInput) })" />
            </label>
            <label class="bk-cell"><span class="t-label-s muted">{{ tr('Expiry', 'মেয়াদ') }}</span>
              <input class="input" type="month" :value="l.expiry" @input="set(l.key, { expiry: inputValue($event) })" />
            </label>
            <Button small variant="text" icon="close" :aria-label="tr('Remove', 'সরান') + ': ' + l.name" @click="lines = lines.filter((x) => x.key !== l.key)">{{ tr('Remove', 'সরান') }}</Button>
          </div>
        </div>
        <div class="row wrap gap-8">
          <span class="grow t-title-s num">{{ ready.length ? tr('Total ', 'মোট ') + money(total) : '' }}</span>
          <Button icon="check_circle" :busy="busy" :disabled="!ready.length || bad" @click="save">{{ tr('Save', 'সেভ করুন') }}</Button>
        </div>
        <ErrorText :text="err" />
      </Panel>
    </div>
    <aside class="col gap-16 rxp-side">
      <Panel :title="tr('Bought before', 'আগের কেনা')" icon="history" :count="list.data ? num(list.data.items.length) : undefined">
        <template v-if="!list.data">
          <ErrorText v-if="list.error" :text="errorText(list.error)" />
          <Loading v-else />
        </template>
        <p v-else-if="!list.data.items.length" class="t-body-m muted">{{ tr('Nothing yet', 'এখনো কিছু নেই') }}</p>
        <ul v-else class="bk-list">
          <li v-for="p in list.data.items.slice(0, 60)" :key="p.id">
            <div class="row">
              <span class="semi grow">{{ p.supplier || tr('Stock in', 'মাল কেনা') }}</span>
              <span class="num semi">{{ money(p.total) }}</span>
              <Button small variant="danger-text" icon="delete" @click="deleting = p">{{ tr('Delete', 'মুছুন') }}</Button>
            </div>
            <span class="t-body-s muted">{{ formatDayOf(p.at) }} · {{ p.lines.map((l) => `${l.name} ×${qtyText(l.qty)}`).join(', ') }}</span>
          </li>
        </ul>
      </Panel>
    </aside>
    <ConfirmDialog
      v-if="deleting"
      :title="tr('Delete this entry?', 'এই কেনা মুছবেন?')"
      :text="tr('The quantities will be taken off the stock.', 'পরিমাণগুলো স্টক থেকে বাদ যাবে।')"
      :confirm="tr('Delete', 'মুছুন')"
      danger
      @close="deleting = null"
      @confirm="del"
    />
  </div>
</template>
