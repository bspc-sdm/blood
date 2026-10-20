<script setup lang="ts">
import { computed } from 'vue';
import { Tag } from '@/ui/kit';
import { num, tr } from '@/lib/i18n';
import { formatDate } from '@/lib/format';
import { imgSrc } from './common';
import { flagLabel, type LabOrder, type LabResult } from './rxactivity';

const props = defineProps<{ o: LabOrder; history: Record<string, { result: string; flag: string; at: number }[]> }>();
const r = computed(() => props.o.report);
const groups = computed(() => {
  const out: { name: string; rows: LabResult[] }[] = [];
  for (const it of r.value?.items ?? []) {
    const g = it.group ?? '';
    if (out.length && out[out.length - 1].name === g) out[out.length - 1].rows.push(it);
    else out.push({ name: g, rows: [it] });
  }
  return out;
});
const prevOf = (it: LabResult) => (it.code ? props.history[`${it.code}.${it.key}`] ?? [] : []);
const prevText = (it: LabResult) =>
  tr('Before: ', 'আগে: ') + prevOf(it).slice(0, 3).map((h) => num(h.result) + (h.flag === 'high' ? '↑' : h.flag === 'low' ? '↓' : '') + ' (' + formatDate(h.at) + ')').join(', ');
</script>

<template>
  <div class="col gap-12">
    <div class="col gap-2">
      <span class="t-title-l">{{ o.labName }}</span>
      <span class="t-body-m muted">{{ [o.patientName, o.patientAge != null ? tr(`${o.patientAge} yrs`, `${num(o.patientAge)} বছর`) : null, r?.reportedAt ? formatDate(r.reportedAt) : null].filter(Boolean).join(' · ') }}</span>
    </div>
    <div v-for="(g, gi) in groups" :key="gi" class="card col">
      <span v-if="g.name" class="t-title-s c-primary">{{ g.name }}</span>
      <template v-for="(it, i) in g.rows" :key="i">
        <div v-if="it.long" class="col gap-2">
          <span class="t-body-l semi">{{ it.test }}</span>
          <p class="t-body-m pre">{{ it.result }}</p>
        </div>
        <div v-else class="row health-result">
          <div class="col gap-2 grow">
            <span class="t-body-l semi">{{ it.test }}</span>
            <span v-if="it.range" class="t-body-s muted">{{ tr('Normal: ', 'স্বাভাবিক: ') + it.range }}</span>
            <span v-if="prevOf(it).length" class="t-body-s muted">{{ prevText(it) }}</span>
          </div>
          <span :class="it.flag ? 't-body-l semi c-error none' : 't-body-l semi none'">{{ it.result + (it.unit ? ' ' + it.unit : '') }}</span>
          <Tag v-if="it.flag" tone="error">{{ flagLabel(it.flag) }}</Tag>
        </div>
      </template>
    </div>
    <p v-if="r?.note" class="t-body-m pre">{{ r.note }}</p>
    <img v-for="(p, i) in r?.photos ?? []" :key="i" :src="imgSrc(p)" :alt="tr('Report photo', 'রিপোর্টের ছবি')" class="health-report-photo" />
  </div>
</template>
