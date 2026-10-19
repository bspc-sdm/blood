<script setup lang="ts">
// মালিকের ফর্মে "ভাড়ার তালিকা": নিজের উপজেলা, গন্তব্যের সারি (সর্বোচ্চ ১৫টা), বাড়তি
import { onMounted } from 'vue';
import { Button, Field, IconButton } from '@/ui/kit';
import { num, tr } from '@/lib/i18n';
import { ensureAreas } from '@/lib/geo';
import { MAX_FARE_ROWS, keyOf, type ChartDraft, type RowDraft } from './fare';
import DestPicker from './DestPicker.vue';
import AmountInput from './AmountInput.vue';

const props = defineProps<{ district: string | null; upazilaId: number | null; value: ChartDraft }>();
const emit = defineEmits<{ change: [d: ChartDraft] }>();
onMounted(() => ensureAreas().catch(() => {}));
const set = (p: Partial<ChartDraft>) => emit('change', { ...props.value, ...p });
const setRow = (i: number, p: Partial<RowDraft>) => set({ rows: props.value.rows.map((r, j) => (j === i ? { ...r, ...p } : r)) });
const used = (i: number) => props.value.rows.filter((_, j) => j !== i).map((r) => keyOf(r.dest)).filter((k) => k && k !== 'h');
</script>

<template>
  <Field :label="tr('Fare list (helps patients choose by budget)', 'ভাড়ার তালিকা (রোগী বাজেট দিয়ে খুঁজতে পারেন)')">
    <div class="col gap-8 fare-chart">
      <AmountInput :label="tr('Within own upazila', 'নিজের উপজেলার ভেতরে')" :model-value="value.local" @update:model-value="(v) => set({ local: v })" />
      <template v-if="district">
        <div v-for="(r, i) in value.rows" :key="i" class="fare-row">
          <DestPicker
            :label="tr(`Destination ${i + 1}`, `গন্তব্য ${num(i + 1)}`)"
            :district="district"
            :value="r.dest"
            :exclude="[...used(i), ...(upazilaId ? [`u:${upazilaId}`] : [])]"
            @change="(d) => setRow(i, { dest: d })"
          />
          <div class="row gap-8 fare-row-amt">
            <AmountInput :label="tr('Fare', 'ভাড়া')" :model-value="r.amount" @update:model-value="(v) => setRow(i, { amount: v })" />
            <IconButton icon="delete" :label="tr('Remove', 'সরান')" @click="set({ rows: value.rows.filter((_, j) => j !== i) })" />
          </div>
        </div>
      </template>
      <span v-else class="t-body-s muted">{{ tr('Choose the base area first.', 'আগে "কোথা থেকে চলে" বেছে নিন।') }}</span>
      <div v-if="district && value.rows.length < MAX_FARE_ROWS">
        <Button variant="outline" small icon="add" @click="set({ rows: [...value.rows, { dest: null, amount: '' }] })">{{ tr('Add a destination', 'গন্তব্য যোগ করুন') }}</Button>
      </div>
      <span class="t-label-l muted">{{ tr('Extras (optional, added on top)', 'বাড়তি (ঐচ্ছিক, ভাড়ার সাথে যোগ হয়)') }}</span>
      <div class="work-three">
        <AmountInput :label="tr('Oxygen', 'অক্সিজেন')" :model-value="value.oxygen" @update:model-value="(v) => set({ oxygen: v })" />
        <AmountInput :label="tr('ICU equipment', 'আইসিইউ যন্ত্র')" :model-value="value.icu" @update:model-value="(v) => set({ icu: v })" />
        <AmountInput :label="tr('Night (10 pm–6 am)', 'রাতে (রাত ১০টা–সকাল ৬টা)')" :model-value="value.night" @update:model-value="(v) => set({ night: v })" />
      </div>
    </div>
  </Field>
</template>
