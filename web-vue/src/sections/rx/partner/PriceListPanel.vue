<script setup lang="ts">
// পরীক্ষা ও দাম — তালিকার ঘর (LabPricesEditor-এর অংশ)
import { computed } from 'vue';
import { Button, ErrorText, Icon, IconButton, cx } from '@/ui/kit';
import { isBangla, num, tr } from '@/lib/i18n';
import { timeAgo } from '@/lib/format';
import Panel from './Panel.vue';
import { TAT, priceOf, type PriceRow } from './labprices';

const props = defineProps<{ rows: PriceRow[]; wide: boolean; tried: boolean; bn: Map<string, string>; err: string | null; busy: boolean; dirty: boolean; data: { updatedAt: number } }>();
const emit = defineEmits<{ set: [id: number, patch: Partial<PriceRow>]; remove: [r: PriceRow]; save: [] }>();
const on = computed(() => props.rows.filter((r) => r.on).length);
const badPrice = (r: PriceRow) => props.tried && (priceOf(r.price) == null || Number.isNaN(priceOf(r.price)));
const val = (e: Event) => (e.target as HTMLInputElement).value;
const set = (id: number, patch: Partial<PriceRow>) => emit('set', id, patch);
const remove = (r: PriceRow) => emit('remove', r);
const save = () => emit('save');
</script>

<template>
<Panel :title="tr('Tests & prices', 'পরীক্ষা ও দাম')" icon="payments" :count="rows.length ? tr(`${on} tests`, `${num(on)}টা পরীক্ষা`) : undefined">
  <datalist id="lp-tat"><option v-for="t in TAT()" :key="t" :value="t" /></datalist>
  <div v-if="!rows.length" class="rxp-empty-card">
    <Icon name="payments" />
    <span>{{ tr('No tests yet — add from the list', 'এখনো কোনো পরীক্ষা নেই — তালিকা থেকে যোগ করুন') }}</span>
  </div>
  <div v-else-if="wide" class="table-wrap rxp-entry">
    <table class="table rxp-table lp-table">
      <thead>
        <tr>
          <th>{{ tr('Test', 'পরীক্ষা') }}</th>
          <th>{{ tr('Price (Tk)', 'দাম (৳)') }}</th>
          <th>{{ tr('Report in', 'রিপোর্ট কতক্ষণে') }}</th>
          <th>{{ tr('Now', 'এখন') }}</th>
          <th><span class="sr-only">{{ tr('Remove', 'সরান') }}</span></th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="(r, i) in rows" :key="r.id" :class="cx(!r.on && 'rxp-done')">
          <td>
            <div class="col gap-2">
              <span :class="cx('semi', !r.on && 'muted')">{{ r.name }}</span>
              <span v-if="r.hidden" class="t-body-s c-error">{{ tr('Removed from the test list — patients don\'t see it; save to keep it as your own test', 'পরীক্ষার ছক থেকে সরানো — রোগীরা দেখেন না; সেভ করলে নিজে লেখা হিসেবে থাকবে') }}</span>
              <span v-else-if="isBangla() && r.code && bn.get(r.code)" class="t-body-s muted">{{ bn.get(r.code) }}</span>
              <span v-else-if="!r.code" class="t-body-s muted">{{ tr('Own test', 'নিজে লেখা') }}</span>
            </div>
          </td>
          <td>
            <input
              :class="cx('input rxp-cell num lp-price', badPrice(r) && 'bad')"
              inputmode="numeric"
              maxlength="8"
              :value="r.price"
              placeholder="৳"
              :data-lp-price="i === 0 ? '' : undefined"
              :aria-label="`${r.name}: ${tr('Price (Tk)', 'দাম (৳)')}`"
              @input="set(r.id, { price: val($event) })"
            />
          </td>
          <td><input class="input rxp-cell lp-tat" list="lp-tat" maxlength="30" :value="r.tat" :placeholder="tr('e.g. Same day', 'যেমন: একই দিন')" :aria-label="`${r.name}: ${tr('Report in', 'রিপোর্ট কতক্ষণে')}`" @input="set(r.id, { tat: val($event) })" /></td>
          <td>
            <label class="lp-on"><input type="checkbox" :checked="r.on" @change="set(r.id, { on: ($event.target as HTMLInputElement).checked })" /><span>{{ r.on ? tr('Done here', 'হয়') : tr('Not now', 'এখন হয় না') }}</span></label>
          </td>
          <td class="rxp-td-actions"><IconButton small icon="close" :label="tr('Remove', 'সরান')" @click="remove(r)" /></td>
        </tr>
      </tbody>
    </table>
  </div>
  <div v-else class="col gap-8">
    <div v-for="(r, i) in rows" :key="r.id" class="rxp-entry-card">
      <div class="row top">
        <div class="grow">
          <div class="col gap-2">
            <span :class="cx('semi', !r.on && 'muted')">{{ r.name }}</span>
            <span v-if="r.hidden" class="t-body-s c-error">{{ tr('Removed from the test list — patients don\'t see it; save to keep it as your own test', 'পরীক্ষার ছক থেকে সরানো — রোগীরা দেখেন না; সেভ করলে নিজে লেখা হিসেবে থাকবে') }}</span>
            <span v-else-if="isBangla() && r.code && bn.get(r.code)" class="t-body-s muted">{{ bn.get(r.code) }}</span>
            <span v-else-if="!r.code" class="t-body-s muted">{{ tr('Own test', 'নিজে লেখা') }}</span>
          </div>
        </div>
        <IconButton small icon="close" :label="tr('Remove', 'সরান')" @click="remove(r)" />
      </div>
      <div class="row gap-8">
        <div class="lp-price-wrap">
          <input
            :class="cx('input rxp-cell num lp-price', badPrice(r) && 'bad')"
            inputmode="numeric"
            maxlength="8"
            :value="r.price"
            placeholder="৳"
            :data-lp-price="i === 0 ? '' : undefined"
            :aria-label="`${r.name}: ${tr('Price (Tk)', 'দাম (৳)')}`"
            @input="set(r.id, { price: val($event) })"
          />
        </div>
        <div class="grow"><input class="input rxp-cell lp-tat" list="lp-tat" maxlength="30" :value="r.tat" :placeholder="tr('e.g. Same day', 'যেমন: একই দিন')" :aria-label="`${r.name}: ${tr('Report in', 'রিপোর্ট কতক্ষণে')}`" @input="set(r.id, { tat: val($event) })" /></div>
      </div>
      <label class="lp-on"><input type="checkbox" :checked="r.on" @change="set(r.id, { on: ($event.target as HTMLInputElement).checked })" /><span>{{ r.on ? tr('Done here', 'হয়') : tr('Not now', 'এখন হয় না') }}</span></label>
    </div>
  </div>
  <ErrorText :text="err" />
  <div class="row wrap gap-8">
    <span class="t-body-s muted grow">{{ data.updatedAt ? tr('Last saved ', 'শেষ সেভ ') + timeAgo(data.updatedAt) : '' }}</span>
    <Button icon="check_circle" :busy="busy" :disabled="!dirty" @click="save">{{ tr('Save', 'সেভ করুন') }}</Button>
  </div>
</Panel>
</template>
