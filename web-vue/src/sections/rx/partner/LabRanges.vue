<script setup lang="ts">
// প্যাথলজি — নিজের মেশিনের স্বাভাবিক মাত্রা (lab_ranges_set): পরীক্ষা বেছে ঘরগুলোর নিচ/ওপর লিখে রাখা,
// বা ছকের মাত্রায় ফেরানো। পরের রিপোর্টের ফর্মে এটাই ভরা থাকে (অ্যাপ/প্যানেলে রিপোর্ট দেওয়ার সময়ের "আমার ল্যাবের জন্য রাখুন"-এর মতো)।
import { computed, ref } from 'vue';
import { Button, ErrorBox, SkeletonCards } from '@/ui/kit';
import { errorText } from '@/lib/api';
import { useCall } from '@/lib/query';
import { tr } from '@/lib/i18n';
import { useWide } from './common';
import { isNumericParam, type TestDef } from './model';
import RangeTestList from './RangeTestList.vue';
import RangeEditor from './RangeEditor.vue';

const wide = useWide();
const q = useCall<{ tests: TestDef[] }>('labtests_get', {}, { staleTime: 10 * 60_000 });
const find = ref('');
const sel = ref<string | null>(null);
const saved = ref<Record<string, 'own' | 'reset'>>({});
const tests = computed(() => (q.data?.tests ?? []).filter((t) => !t.hidden && t.params.some(isNumericParam)));
const shown = computed(() => {
  const t = find.value.trim().toLowerCase();
  return t ? tests.value.filter((x) => [x.name, x.bn ?? '', x.code, ...(x.aliases ?? [])].some((s) => s.toLowerCase().includes(t))) : tests.value;
});
const cur = computed(() => tests.value.find((x) => x.code === sel.value) ?? (wide.value ? shown.value[0] : undefined));
function pick(code: string) {
  sel.value = code;
  if (!wide.value) window.scrollTo({ top: 0 });
}
const onSaved = (kind: 'own' | 'reset') => (saved.value = { ...saved.value, [cur.value!.code]: kind });
</script>

<template>
  <SkeletonCards v-if="q.isLoading" :n="2" :h="200" />
  <ErrorBox v-else-if="q.error" :error="errorText(q.error)" :on-retry="() => q.refetch()" />
  <div v-else class="col gap-12">
    <p class="t-body-s muted">
      {{
        tr(
          'If your machine\'s normal range is different, write it here — the next report form will be filled with it (for both men and women). You can also change it while giving a report.',
          'আপনার মেশিনের স্বাভাবিক মাত্রা আলাদা হলে এখানে লিখে রাখুন — পরের রিপোর্টের ফর্মে এটাই ভরা থাকবে (নারী-পুরুষ দুজনের জন্যই)। রিপোর্ট দেওয়ার সময়ও বদলে রাখা যায়।',
        )
      }}
    </p>
    <template v-if="!wide">
      <template v-if="sel && cur">
        <div class="row">
          <Button variant="text" small icon="arrow_back" @click="sel = null">{{ tr('All tests', 'সব পরীক্ষা') }}</Button>
        </div>
        <RangeEditor :key="cur.code" :test="cur" @saved="onSaved" />
      </template>
      <RangeTestList v-else :shown="shown" :cur-code="cur?.code ?? null" :saved="saved" :find="find" @find="(v) => (find = v)" @pick="pick" />
    </template>
    <div v-else class="rxp-grid ranges">
      <RangeTestList :shown="shown" :cur-code="cur?.code ?? null" :saved="saved" :find="find" @find="(v) => (find = v)" @pick="pick" />
      <RangeEditor v-if="cur" :key="cur.code" :test="cur" @saved="onSaved" />
      <span v-else />
    </div>
  </div>
</template>
