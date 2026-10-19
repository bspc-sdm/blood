<script setup lang="ts">
// একটা পরীক্ষার নিজের মাত্রা
import { computed, ref } from 'vue';
import { Button, ConfirmDialog, ErrorText, cx, toast } from '@/ui/kit';
import { call, errorText } from '@/lib/api';
import { isBangla, tr, trPlain } from '@/lib/i18n';
import Panel from './Panel.vue';
import { defRangeText, isNumericParam, parseNum, type TestDef } from './model';

const props = defineProps<{ test: TestDef }>();
const emit = defineEmits<{ saved: [kind: 'own' | 'reset'] }>();
const params = computed(() => props.test.params.filter(isNumericParam));
const vals = ref<Record<string, { low: string; high: string }>>({});
const busy = ref(false);
const err = ref<string | null>(null);
const resetting = ref(false);
const edited = computed(() => params.value.filter((p) => vals.value[p.key] && (vals.value[p.key].low.trim() || vals.value[p.key].high.trim())));
const ph = (p: TestDef['params'][number], i: 0 | 1) => {
  const r = p.r ?? p.m ?? p.f;
  const v = r ? r[i] : null;
  return v == null ? '' : String(v);
};
const vOf = (key: string) => vals.value[key] ?? { low: '', high: '' };
const setV = (key: string, patch: Partial<{ low: string; high: string }>) => (vals.value = { ...vals.value, [key]: { ...vOf(key), ...patch } });

async function save() {
  const ranges: { code: string; key: string; low: number | null; high: number | null }[] = [];
  for (const p of edited.value) {
    const lo = parseNum(vals.value[p.key].low);
    const hi = parseNum(vals.value[p.key].high);
    if (Number.isNaN(lo) || Number.isNaN(hi) || (lo == null && hi == null) || (lo != null && hi != null && lo > hi)) {
      err.value = tr(`${p.name}: check the normal range`, `${p.name}: মাত্রা দেখে নিন`);
      return;
    }
    ranges.push({ code: props.test.code, key: p.key, low: lo, high: hi });
  }
  if (!ranges.length) return;
  busy.value = true;
  err.value = null;
  try {
    await call('lab_ranges_set', { ranges });
    vals.value = {};
    emit('saved', 'own');
    toast(tr('Saved — the next report will have these', 'রাখা হয়েছে — পরের রিপোর্টে এটাই আসবে'));
  } catch (e) {
    err.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
async function reset() {
  busy.value = true;
  err.value = null;
  try {
    await call('lab_ranges_set', { ranges: params.value.map((p) => ({ code: props.test.code, key: p.key, low: null, high: null })) });
    vals.value = {};
    resetting.value = false;
    emit('saved', 'reset');
    toast(tr('Back to the standard ranges', 'ছকের মাত্রায় ফেরানো হয়েছে'));
  } catch (e) {
    err.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
const val = (e: Event) => (e.target as HTMLInputElement).value;
</script>

<template>
  <Panel :title="test.name" icon="tune">
    <span v-if="isBangla() && test.bn" class="t-body-s muted">{{ test.bn }}</span>
    <div class="table-wrap rxp-entry">
      <table class="table rxp-table">
        <thead>
          <tr>
            <th>{{ tr('Row', 'ঘর') }}</th>
            <th>{{ tr('Standard range', 'ছকের মাত্রা') }}</th>
            <th>{{ tr('My lab', 'আমার ল্যাব') }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="p in params" :key="p.key">
            <td>
              <div class="col gap-2">
                <span class="semi">{{ p.name }}</span>
                <span v-if="p.unit" class="t-body-s muted">{{ p.unit }}</span>
              </div>
            </td>
            <td class="t-body-s num">{{ defRangeText(p) || '—' }}</td>
            <td>
              <div class="rxp-range">
                <input :class="cx('input rxp-cell num', Number.isNaN(parseNum(vOf(p.key).low)) && 'bad')" inputmode="decimal" maxlength="10" :value="vOf(p.key).low" :placeholder="ph(p, 0) || tr('Low', 'নিচে')" :aria-label="`${p.name}: ${tr('Low', 'নিচে')}`" @input="setV(p.key, { low: val($event) })" />
                <span class="muted">–</span>
                <input :class="cx('input rxp-cell num', Number.isNaN(parseNum(vOf(p.key).high)) && 'bad')" inputmode="decimal" maxlength="10" :value="vOf(p.key).high" :placeholder="ph(p, 1) || tr('High', 'ওপরে')" :aria-label="`${p.name}: ${tr('High', 'ওপরে')}`" @input="setV(p.key, { high: val($event) })" />
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <p class="t-body-s muted">{{ tr('Leave a row empty to keep it as it is.', 'যে ঘর খালি রাখবেন, সেটা আগের মতোই থাকবে।') }}</p>
    <ErrorText :text="err" />
    <div class="row wrap gap-8">
      <Button variant="text" small :disabled="busy" @click="resetting = true">{{ tr('Back to standard ranges', 'ছকের মাত্রায় ফেরান') }}</Button>
      <span class="grow" />
      <Button :busy="busy" :disabled="!edited.length" @click="save">{{ tr('Keep for my lab', 'আমার ল্যাবের জন্য রাখুন') }}</Button>
    </div>
    <ConfirmDialog
      v-if="resetting"
      :title="tr('Use the standard ranges?', 'ছকের মাত্রায় ফেরাবেন?')"
      :text="trPlain(`Your own ranges for ${test.name} will be removed.`, `${test.name}-এর জন্য রাখা আপনার নিজের মাত্রা মুছে যাবে।`)"
      :confirm="tr('Back to standard', 'ছকের মাত্রায় ফেরান')"
      :busy="busy"
      :error="err"
      @confirm="reset"
      @close="resetting = false"
    />
  </Panel>
</template>
