<script setup lang="ts">
// ব্লাড ব্যাংকের মালিক ("আমার কাজ"): ৮টা গ্রুপের আছে/কম/নেই — চাপলেই সেভ (bloodbank_stock_set, পুরো অবস্থা পাঠায়;
// আবার চাপলে "জানা নেই"), আর "সব ঠিক আছে — এখন হালনাগাদ" (refresh: হালনাগাদের সময় নতুন, বদল ছাড়া)।
import { ref, watch } from 'vue';
import { Button, ErrorText, toast } from '@/ui/kit';
import { call, errorText } from '@/lib/api';
import { BLOOD_GROUPS, LT, type StockLevel } from './model';

type Groups = Record<string, StockLevel | null>;
export interface StockResult { stock: Groups | null; stockUpdatedAt: number | null; stockStale: boolean }
const props = defineProps<{ id: string; stock: Groups | null | undefined }>();
const emit = defineEmits<{ saved: [r: StockResult] }>();
const LEVELS: StockLevel[] = ['have', 'low', 'none'];
const cur = ref<Groups>({});
watch(
  () => props.stock,
  (s) => (cur.value = Object.fromEntries(BLOOD_GROUPS.map((g) => [g, s?.[g] ?? null]))),
  { immediate: true },
);
const busy = ref(false);
const err = ref<string | null>(null);
async function send(body: Record<string, unknown>) {
  busy.value = true;
  err.value = null;
  try {
    const r = await call<StockResult>('bloodbank_stock_set', { id: props.id, ...body });
    if (r?.stock) cur.value = { ...cur.value, ...r.stock };
    emit('saved', { stock: r?.stock ?? cur.value, stockUpdatedAt: r?.stockUpdatedAt ?? Date.now(), stockStale: r?.stockStale ?? false });
    toast(LT.stockSaved());
  } catch (e) {
    err.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
function pick(g: string, l: StockLevel) {
  const next = { ...cur.value, [g]: cur.value[g] === l ? null : l };
  cur.value = next;
  send({ groups: next });
}
</script>

<template>
  <div class="col gap-8" data-testid="stock-editor">
    <div class="pv-stock">
      <div v-for="g in BLOOD_GROUPS" :key="g" class="pv-stock-row">
        <b>{{ g }}</b>
        <span class="seg" role="group" :aria-label="g">
          <button
            v-for="l in LEVELS"
            :key="l"
            type="button"
            :class="l"
            :aria-pressed="cur[g] === l"
            :disabled="busy"
            :data-testid="`stock-${g}-${l}`"
            @click="pick(g, l)"
          >{{ LT.stock(l) }}</button>
        </span>
      </div>
    </div>
    <div class="row wrap gap-8">
      <Button variant="tonal" icon="refresh" :busy="busy" :disabled="!stock" data-testid="stock-refresh" @click="send({ refresh: true })">{{ LT.stockAllOk() }}</Button>
    </div>
    <ErrorText :text="err" />
  </div>
</template>
