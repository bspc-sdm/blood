<script setup lang="ts">
// দেওয়া রিপোর্ট: দেখা ও ছাপা
import { computed } from 'vue';
import { Button, Tag, cx } from '@/ui/kit';
import { num, tr } from '@/lib/i18n';
import { formatDateTime, formatDayOf } from '@/lib/format';
import { ageText, flagArrow, flagText, genderText, type HistoryPoint, type LabOrder, type LabResult } from './model';

const props = defineProps<{ o: LabOrder; history: Record<string, HistoryPoint[]> }>();
const r = computed(() => props.o.report);
const groups = computed(() => {
  const out: { name: string; rows: LabResult[] }[] = [];
  for (const i of r.value?.items ?? []) {
    const g = i.group || '';
    const last = out[out.length - 1];
    if (last && last.name === g) last.rows.push(i);
    else out.push({ name: g, rows: [i] });
  }
  return out;
});
const prev = (i: LabResult) => {
  const list = i.code && i.key ? props.history[`${i.code}.${i.key}`] ?? [] : [];
  return list.slice(0, 3).map((h) => `${num(h.result)}${flagArrow(h.flag)} (${formatDayOf(h.at)})`).join(', ');
};
const hasHist = computed(() => (r.value?.items ?? []).some((i) => prev(i)));
const print = () => window.print();
</script>

<template>
  <section v-if="r" class="card rxp-panel rxp-print">
    <div class="rxp-rx-head">
      <div class="col gap-4 grow">
        <span class="t-title-l">{{ o.labName }}</span>
        <span class="t-body-m">{{ [o.patientName, ageText(o.patientAge), genderText(o.patientGender)].filter(Boolean).join(' · ') }}</span>
        <span class="t-body-s muted">{{ [o.doctorName, r.reportedAt ? tr('Report: ', 'রিপোর্ট: ') + formatDateTime(r.reportedAt) : ''].filter(Boolean).join(' · ') }}</span>
      </div>
      <Button variant="tonal" icon="share" class="rxp-noprint" @click="print">{{ tr('Print', 'ছাপুন') }}</Button>
    </div>
    <div v-for="(g, gi) in groups" :key="gi" class="table-wrap rxp-entry">
      <div v-if="g.name" class="rxp-group">{{ g.name }}</div>
      <table class="table rxp-table rxp-report">
        <thead>
          <tr>
            <th>{{ tr('Test', 'পরীক্ষা') }}</th>
            <th>{{ tr('Result', 'ফল') }}</th>
            <th>{{ tr('Normal', 'স্বাভাবিক') }}</th>
            <th><span class="sr-only">{{ tr('High/low', 'বেশি/কম') }}</span></th>
            <th v-if="hasHist">{{ tr('Before', 'আগে') }}</th>
          </tr>
        </thead>
        <tbody>
          <template v-for="(i, k) in g.rows" :key="k">
            <tr v-if="i.long">
              <td class="semi">{{ i.test }}</td>
              <td :colspan="hasHist ? 4 : 3" class="pre">{{ i.result }}</td>
            </tr>
            <tr v-else>
              <td class="semi">{{ i.test }}</td>
              <td :class="cx('num rxp-nowrap', i.flag && 'c-error semi')">{{ i.result }}<span v-if="i.unit" class="muted">{{ " " + i.unit }}</span></td>
              <td class="t-body-s muted">{{ i.range || '—' }}</td>
              <td><Tag v-if="i.flag" tone="error">{{ flagText(i.flag) }}</Tag></td>
              <td v-if="hasHist" class="t-body-s muted">{{ prev(i) || '—' }}</td>
            </tr>
          </template>
        </tbody>
      </table>
    </div>
    <p v-if="r.note" class="t-body-m pre">{{ r.note }}</p>
    <div v-if="(r.photos ?? []).length" class="rxp-photos">
      <img v-for="(p, i) in r.photos ?? []" :key="i" :src="`data:image/jpeg;base64,${p}`" :alt="tr('Report photo', 'রিপোর্টের ছবি')" />
    </div>
  </section>
</template>
