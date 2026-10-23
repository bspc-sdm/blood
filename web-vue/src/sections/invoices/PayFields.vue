<script setup lang="ts">
// পরিশোধ: মাধ্যম (নগদ, বিকাশ, নগদ (মোবাইল), কার্ড, অন্য), কত পেলেন, ফেরত/বাকি। বিকাশ/নগদ/কার্ডে লেনদেনের আইডি (ঐচ্ছিক)।
// "কত পেলেন" খালি = পুরো টাকা পেয়েছেন।
import { computed } from 'vue';
import { inputValue } from '@/ui/kit';
import { numInput, readNum } from '@/sections/rx/partner/books/model';
import { T } from './text';
import { PAY_METHODS, changeOf, methodText, money, type PayMethod } from './model';

const props = defineProps<{ total: number; noGiven?: boolean }>();
const method = defineModel<PayMethod>('method', { required: true });
const given = defineModel<string>('given', { default: '' });
const ref_ = defineModel<string>('txRef', { default: '' });
const g = computed(() => readNum(given.value));
const c = computed(() => changeOf(props.total, g.value == null || Number.isNaN(g.value) ? null : g.value));
</script>

<template>
  <div class="col gap-8 inv-pay">
    <div class="chips" role="group" :aria-label="T.method()">
      <button v-for="m in PAY_METHODS" :key="m" type="button" class="chip" :aria-pressed="method === m" @click="method = m">{{ methodText(m) }}</button>
    </div>
    <div class="row wrap gap-8">
      <label v-if="!noGiven" class="bk-cell inv-given">
        <span class="t-label-s muted">{{ T.given() }}</span>
        <input class="input num" inputmode="decimal" :value="given" :placeholder="money(total)" data-testid="pay-given" @input="given = inputValue($event, numInput)" />
      </label>
      <label v-if="method !== 'cash'" class="bk-cell inv-ref">
        <span class="t-label-s muted">{{ T.txRef() }}</span>
        <input class="input" :value="ref_" maxlength="40" @input="ref_ = ($event.target as HTMLInputElement).value" />
      </label>
      <span v-if="!noGiven && c.change > 0" class="inv-change num" data-testid="pay-change">{{ T.change() }} {{ money(c.change) }}</span>
      <span v-else-if="!noGiven && c.due > 0" class="inv-change due num" data-testid="pay-due">{{ T.due() }} {{ money(c.due) }}</span>
    </div>
  </div>
</template>
