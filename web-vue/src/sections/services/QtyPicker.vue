<script setup lang="ts">
// "কতটা লাগবে": − [সংখ্যা] + আর ওষুধের ধরন অনুযায়ী একক (ট্যাবলেট/ক্যাপসুল: পাতা বা পিস; সিরাপ: বোতল; ইনজেকশন: টি/ভায়াল;
// বাকি: টি)। ১–৯৯৯। অ্যাপের MedFind.kt QtyPicker-এর ওয়েব রূপ।
import { computed, ref, watch } from 'vue';
import { num, tr } from '@/lib/i18n';
import { parseQty, QTY_MAX, unitName, unitsFor, type QtyUnit } from './medfind';

const props = defineProps<{ form: string; qty: number; unit: QtyUnit }>();
const emit = defineEmits<{ 'update:qty': [v: number]; 'update:unit': [v: QtyUnit] }>();

const units = computed(() => unitsFor(props.form));
// লেখার ঘর নিজের লেখা রাখে (মুছে নতুন সংখ্যা লেখার মাঝে খালি থাকতে পারে); ঠিক সংখ্যা হলেই ওপরে যায়
const text = ref(num(props.qty));
watch(() => props.qty, (q) => {
  if (parseQty(text.value) !== q) text.value = num(q);
});
function onInput(e: Event) {
  text.value = (e.target as HTMLInputElement).value;
  const q = parseQty(text.value);
  if (q != null) emit('update:qty', q);
}
function onBlur() {
  text.value = num(props.qty);
}
// নতুন লেখা (React-এ ছিল না)
const label = computed(() => ({
  title: tr('How much do you need', 'কতটা লাগবে'), // bn-ok
  less: tr('Less', 'কম'), // bn-ok
  more: tr('More', 'বেশি'), // bn-ok
  amount: tr('Amount', 'পরিমাণ'), // bn-ok
  unit: tr('Unit', 'একক'), // bn-ok
}));
const step = (d: number) => emit('update:qty', Math.min(QTY_MAX, Math.max(1, props.qty + d)));
</script>

<template>
  <div class="col gap-8" data-testid="medfind-qty">
    <span class="t-title-s">{{ label.title }}</span>
    <div class="medfind-qty-row">
      <div class="medfind-stepper">
        <button type="button" class="medfind-step" :aria-label="label.less" :disabled="qty <= 1" data-testid="qty-minus" @click="step(-1)">−</button>
        <input
          class="medfind-qty-input"
          :value="text"
          inputmode="numeric"
          maxlength="3"
          autocomplete="off"
          :aria-label="label.amount"
          data-testid="qty-input"
          @input="onInput"
          @blur="onBlur"
        />
        <button type="button" class="medfind-step" :aria-label="label.more" :disabled="qty >= QTY_MAX" data-testid="qty-plus" @click="step(1)">+</button>
      </div>
      <div v-if="units.length > 1" class="seg medfind-units" role="group" :aria-label="label.unit">
        <button v-for="u in units" :key="u" type="button" :aria-pressed="u === unit" :data-testid="`qty-unit-${u}`" @click="emit('update:unit', u)">{{ unitName(u) }}</button>
      </div>
      <span v-else class="t-title-s medfind-unit" :data-testid="`qty-unit-${units[0]}`">{{ unitName(units[0]) }}</span>
    </div>
  </div>
</template>
