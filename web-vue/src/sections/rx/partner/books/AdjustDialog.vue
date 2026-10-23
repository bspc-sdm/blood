<script setup lang="ts">
// নষ্ট/মেয়াদ শেষ (কেনা দামে ক্ষতি) বা গণনা ঠিক করা
import { computed, ref } from 'vue';
import { Button, Dialog, ErrorText, Segmented, TextInput, toast } from '@/ui/kit';
import { call } from '@/lib/api';
import { ownerGate } from '../../../invoices/offline';
import { errText } from '../../../invoices/actions';
import { tr } from '@/lib/i18n';
import { money, numInput, qtyText, readNum, unitText, type ShopItem } from './model';

const props = defineProps<{ item: ShopItem }>();
const emit = defineEmits<{ close: []; done: [] }>();
const kind = ref<'writeoff' | 'count'>('writeoff');
const qty = ref('');
const reason = ref('');
const busy = ref(false);
const err = ref<string | null>(null);
const n = computed(() => readNum(qty.value));
const ok = computed(() => n.value != null && !Number.isNaN(n.value) && (kind.value === 'count' || n.value > 0));
async function save() {
  busy.value = true;
  err.value = null;
  try {
    // ডেস্কটপে কাউন্টারের পিন হলে আগে মালিকের পিন (নিরাপত্তা পর্যালোচনা I7)
    await ownerGate();
    await call('books_adjust', { itemId: props.item.id, kind: kind.value, qty: n.value, reason: reason.value });
    toast(tr('Saved', 'সেভ হয়েছে'));
    emit('done');
  } catch (e) {
    err.value = errText(e);
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <Dialog size="small" :title="item.name" @close="emit('close')">
    <div class="col gap-12">
      <Segmented v-model="kind" :options="[{ value: 'writeoff', label: tr('Damaged / expired', 'নষ্ট / মেয়াদ শেষ') }, { value: 'count', label: tr('Correct the count', 'গণনা ঠিক করা') }]" />
      <span class="t-body-s muted">{{ tr('Now: ', 'এখন: ') }}{{ qtyText(item.qty) }} {{ item.qty != null ? unitText(item.unit) : '' }}</span>
      <TextInput
        :label="kind === 'writeoff' ? tr('How many', 'কয়টা') : tr('Counted', 'গুনে পেলেন')"
        :model-value="qty"
        inputmode="decimal"
        class="input num"
        autofocus
        :hint="kind === 'writeoff' && item.cost != null && n ? tr('Loss ', 'ক্ষতি ') + money(Math.round(n * item.cost * 100) / 100) : undefined"
        @update:model-value="(v) => (qty = numInput(v))"
      />
      <TextInput v-if="kind === 'writeoff'" v-model="reason" :label="tr('Reason (optional)', 'কারণ (ঐচ্ছিক)')" maxlength="120" />
      <ErrorText :text="err" />
    </div>
    <template #actions>
      <Button variant="text" @click="emit('close')">{{ tr('Cancel', 'বাতিল') }}</Button>
      <Button :busy="busy" :disabled="!ok" @click="save">{{ tr('Save', 'সেভ') }}</Button>
    </template>
  </Dialog>
</template>
