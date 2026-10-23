<script setup lang="ts">
// পণ্যের ফর্ম: নাম ছাড়া সব ঐচ্ছিক
import { computed, ref } from 'vue';
import { Button, ConfirmDialog, Dialog, ErrorText, Segmented, Select, TextInput, toast } from '@/ui/kit';
import { call, errorText } from '@/lib/api';
import { ownerGate } from '../../../invoices/offline';
import { errText } from '../../../invoices/actions';
import { tr } from '@/lib/i18n';
import { UNITS, money, numInput, numStr, readNum, unitText, type Disc, type ShopItem } from './model';
import type { Draft } from './types';

const props = defineProps<{ start: Draft }>();
const emit = defineEmits<{ close: []; saved: []; adjust: [i: ShopItem] }>();
const st = props.start;
const edit = st.id ? (st as ShopItem) : null;
const name = ref(st.name);
const unit = ref(st.unit ?? '');
const qty = ref(numStr(st.qty));
const cost = ref(numStr(st.cost));
const price = ref(numStr(st.price));
const dType = ref<'pct' | 'amt'>(st.disc?.type ?? 'pct');
const dVal = ref(numStr(st.disc?.v));
const lowAt = ref(numStr(st.lowAt));
const expiry = ref(st.expiry ?? '');
const busy = ref(false);
const err = ref<string | null>(null);
const confirmDel = ref(false);

const nums = computed(() => ({ qty: readNum(qty.value), cost: readNum(cost.value), price: readNum(price.value), d: readNum(dVal.value), lowAt: readNum(lowAt.value) }));
const bad = computed(() => Object.values(nums.value).some((v) => v != null && Number.isNaN(v)));
const disc = computed<Disc>(() => (nums.value.d ? { type: dType.value, v: nums.value.d } : null));
const after = computed(() => {
  const p = nums.value.price;
  const d = disc.value;
  return p != null && d ? Math.max(0, p - (dType.value === 'pct' ? (p * d.v) / 100 : d.v)) : null;
});
const below = computed(() => {
  const n = nums.value;
  return (n.cost != null && after.value != null && after.value < n.cost) || (n.cost != null && after.value == null && n.price != null && n.price < n.cost);
});

async function save() {
  if (!name.value.trim() || bad.value) return;
  busy.value = true;
  err.value = null;
  try {
    const n = nums.value;
    // দাম বদল (বা নতুন দাম): ডেস্কটপে কাউন্টারের পিন হলে আগে মালিকের পিন (নিরাপত্তা পর্যালোচনা I7)
    if (n.price !== (st.price ?? null) || JSON.stringify(disc.value) !== JSON.stringify(st.disc ?? null)) await ownerGate();
    await call('books_item_save', {
      ...(edit ? { id: edit.id } : {}), name: name.value, generic: st.generic ?? '', form: st.form ?? '', unit: unit.value,
      qty: n.qty, cost: n.cost, price: n.price, disc: disc.value, lowAt: n.lowAt, expiry: expiry.value || null,
    });
    toast(tr('Saved', 'সেভ হয়েছে'));
    emit('saved');
  } catch (e) {
    err.value = errText(e);
  } finally {
    busy.value = false;
  }
}
async function del() {
  try {
    await call('books_item_delete', { id: edit!.id });
    emit('saved');
  } catch (e) {
    err.value = errorText(e);
    confirmDel.value = false;
  }
}
</script>

<template>
  <Dialog :title="edit ? edit.name : tr('New product', 'নতুন পণ্য')" @close="emit('close')">
    <form class="col gap-12" @submit.prevent="save">
      <TextInput v-model="name" :label="tr('Product name', 'পণ্যের নাম')" maxlength="100" :hint="st.generic ? [st.generic, st.form].filter(Boolean).join(' · ') : undefined" />
      <p class="t-body-s muted">{{ tr('Everything below is optional.', 'নিচের সব ঐচ্ছিক।') }}</p>
      <div class="bk-form-grid">
        <TextInput
          :label="tr('Quantity', 'পরিমাণ')"
          :model-value="qty"
          inputmode="decimal"
          class="input num"
          :hint="edit && edit.qty != null ? tr('Change = count correction', 'বদলালে &quot;গণনা ঠিক করা&quot; হিসেবে থাকবে') : undefined"
          @update:model-value="(v) => (qty = numInput(v))"
        />
        <Select v-model="unit" :label="tr('Unit', 'একক')" placeholder="—" :options="UNITS.map((u) => ({ value: u, label: unitText(u) }))" />
        <TextInput :label="tr('Cost price (per unit)', 'কেনা দাম (প্রতি একক)')" :model-value="cost" inputmode="decimal" class="input num" @update:model-value="(v) => (cost = numInput(v))" />
        <TextInput :label="tr('Sale price / MRP (per unit)', 'বিক্রি দাম / MRP (প্রতি একক)')" :model-value="price" inputmode="decimal" class="input num" @update:model-value="(v) => (price = numInput(v))" />
      </div>
      <div class="col gap-6">
        <span class="t-label-l">{{ tr('Discount', 'ছাড়') }}</span>
        <div class="row wrap gap-8">
          <Segmented v-model="dType" :label="tr('Discount', 'ছাড়')" :options="[{ value: 'pct', label: '%' }, { value: 'amt', label: tr('Tk', 'টাকা') }]" />
          <input
            class="input num bk-small-input"
            inputmode="decimal"
            :value="dVal"
            :aria-label="tr('Discount', 'ছাড়')"
            placeholder="০"
            @input="(e) => { const t = e.target as HTMLInputElement; dVal = numInput(t.value); t.value = dVal; }"
          />
          <span v-if="after != null" class="t-body-s c-primary">{{ tr('Sells at ', 'বিক্রি হবে ') }}{{ money(Math.round(after * 100) / 100) }}</span>
        </div>
      </div>
      <div class="bk-form-grid">
        <TextInput :label="tr('Reorder at (qty)', 'কত থাকলে আবার আনবেন')" :model-value="lowAt" inputmode="decimal" class="input num" @update:model-value="(v) => (lowAt = numInput(v))" />
        <TextInput v-model="expiry" :label="tr('Expiry (month/year)', 'মেয়াদ (মাস/বছর)')" type="month" />
      </div>
      <p v-if="below" class="t-body-s c-error">{{ tr('Sells below cost', 'কেনা দামের চেয়ে কমে বিক্রি হবে') }}</p>
      <ErrorText :text="err" />
      <button type="submit" hidden />
    </form>
    <ConfirmDialog
      v-if="confirmDel && edit"
      :title="tr('Delete this product?', 'পণ্যটা মুছবেন?')"
      :text="edit.name"
      :confirm="tr('Delete', 'মুছুন')"
      danger
      @close="confirmDel = false"
      @confirm="del"
    />
    <template #actions>
      <Button v-if="edit" variant="danger-text" @click="confirmDel = true">{{ tr('Delete', 'মুছুন') }}</Button>
      <Button v-if="edit" variant="text" @click="emit('adjust', edit)">{{ tr('Adjust', 'নষ্ট/গণনা') }}</Button>
      <span class="grow" />
      <Button variant="text" @click="emit('close')">{{ tr('Cancel', 'বাতিল') }}</Button>
      <Button :busy="busy" :disabled="!name.trim() || bad" @click="save">{{ tr('Save', 'সেভ') }}</Button>
    </template>
  </Dialog>
</template>
